package com.mongle.backend.global.security;

import com.mongle.backend.domain.auth.repository.RefreshSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class MongleJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final RefreshSessionRepository sessions;
    private final Clock authClock;

    @Override
    @Transactional(readOnly = true)
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        Long sessionId = Long.valueOf(jwt.getClaimAsString("sid"));
        // 토큰에 저장된 과거 상태 대신 현재 세션·온보딩 상태를 확인한다.
        var user = sessions.findActiveUser(sessionId, userId, LocalDateTime.ofInstant(authClock.instant(), ZoneOffset.UTC))
                .orElseThrow(() -> new InvalidBearerTokenException("로그인이 만료되었거나 로그아웃한 계정입니다."));
        var authorities = new ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (user.isOnboardingCompleted()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ONBOARDED"));
        }
        return new JwtAuthenticationToken(jwt, authorities, userId.toString());
    }
}
