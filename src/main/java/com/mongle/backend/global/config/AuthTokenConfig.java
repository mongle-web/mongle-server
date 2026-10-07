package com.mongle.backend.global.config;

import com.mongle.backend.domain.auth.config.AuthProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthTokenConfig {
    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecretKey authSigningKey(AuthProperties properties, Environment environment) {
        byte[] bytes;
        if (properties.secret() == null || properties.secret().isBlank()) {
            if (!environment.acceptsProfiles(Profiles.of("local", "test"))
                    || environment.acceptsProfiles(Profiles.of("mysql", "prod"))) {
                throw new IllegalStateException("운영 환경에는 MONGLE_AUTH_SECRET을 설정해야 합니다.");
            }
            // 로컬·테스트는 실행마다 키가 바뀌므로 기존 토큰을 다시 사용할 수 없다.
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
        } else {
            try {
                bytes = Base64.getDecoder().decode(properties.secret());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("MONGLE_AUTH_SECRET은 Base64 형식이어야 합니다.", exception);
            }
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("서명 키는 디코딩 후 최소 32바이트여야 합니다.");
        }
        if ((!environment.acceptsProfiles(Profiles.of("local", "test"))
                || environment.acceptsProfiles(Profiles.of("mysql", "prod"))) && !properties.cookieSecure()) {
            throw new IllegalStateException("운영 환경의 인증 쿠키는 Secure를 활성화해야 합니다.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey authSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(authSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey authSigningKey, AuthProperties properties) {
        var decoder = NimbusJwtDecoder.withSecretKey(authSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> contract = jwt -> {
            boolean valid = jwt.getAudience() != null && jwt.getAudience().contains(properties.audience())
                    && "access".equals(jwt.getClaimAsString("token_use"))
                    && positiveId(jwt.getSubject()) && positiveId(jwt.getClaimAsString("sid"))
                    && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null;
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "몽글 인증 토큰 형식이 올바르지 않습니다.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), contract));
        return decoder;
    }

    private static boolean positiveId(String value) {
        try {
            return value != null && value.matches("[1-9][0-9]*") && Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(AuthProperties properties) {
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieName("MONGLE_CSRF");
        repository.setCookiePath("/api/v1/auth");
        repository.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite()));
        return repository;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
