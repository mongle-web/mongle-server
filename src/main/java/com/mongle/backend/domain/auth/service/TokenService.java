package com.mongle.backend.domain.auth.service;

import com.mongle.backend.domain.auth.config.AuthProperties;
import com.mongle.backend.domain.auth.dto.TokenResponse;
import com.mongle.backend.domain.auth.entity.RefreshSession;
import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.auth.repository.RefreshSessionRepository;
import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RefreshSessionRepository sessions;
    private final UserRepository users;
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock authClock;

    // 쿠키에 담을 원문 토큰은 응답 DTO에 포함하지 않는다.
    public record IssuedTokens(TokenResponse response, String refreshToken, Instant refreshExpiresAt) { }

    @Transactional
    public IssuedTokens login(Long userId) {
        var user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
        Instant now = authClock.instant();
        String refresh = randomToken();
        var session = sessions.saveAndFlush(RefreshSession.create(user, hash(refresh),
                LocalDateTime.ofInstant(now.plus(properties.refreshTtl()), ZoneOffset.UTC)));
        return issue(session, refresh, now);
    }

    @Transactional
    public IssuedTokens refresh(String refreshToken) {
        validateRefresh(refreshToken);
        var session = sessions.findByTokenHashForUpdate(hash(refreshToken))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN));
        Instant now = authClock.instant();
        if (session.isExpired(LocalDateTime.ofInstant(now, ZoneOffset.UTC))) {
            throw new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
        String replacement = randomToken();
        session.rotate(hash(replacement));
        sessions.flush();
        return issue(session, replacement, now);
    }

    @Transactional
    public void logout(String refreshToken) {
        // 이미 로그아웃했거나 쿠키가 없어도 같은 성공 응답을 반환한다.
        if (!isRefreshToken(refreshToken)) {
            return;
        }
        sessions.findByTokenHashForUpdate(hash(refreshToken)).ifPresent(sessions::delete);
    }

    private IssuedTokens issue(RefreshSession session, String refresh, Instant now) {
        Instant sessionExpiry = session.getExpiresAt().toInstant(ZoneOffset.UTC);
        Instant accessExpiry = now.plus(properties.accessTtl());
        if (sessionExpiry.isBefore(accessExpiry)) {
            accessExpiry = sessionExpiry;
        }
        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer()).audience(List.of(properties.audience()))
                .subject(session.getUser().getId().toString())
                .issuedAt(now).expiresAt(accessExpiry).id(UUID.randomUUID().toString())
                .claim("token_use", "access").claim("sid", session.getId().toString()).build();
        String access = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        var response = new TokenResponse(access, "Bearer", accessExpiry.getEpochSecond() - now.getEpochSecond(),
                UserResponse.from(session.getUser()));
        return new IssuedTokens(response, refresh, sessionExpiry);
    }

    private void validateRefresh(String token) {
        if (!isRefreshToken(token)) {
            throw new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    private boolean isRefreshToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("토큰 해시를 생성할 수 없습니다.", exception);
        }
    }
}
