package com.mongle.backend.domain.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties("mongle.auth")
public record AuthProperties(
        String secret,
        @NotBlank(message = "토큰 발급자는 필수입니다.") String issuer,
        @NotBlank(message = "토큰 대상은 필수입니다.") String audience,
        @NotNull(message = "토큰 유효기간은 필수입니다.") Duration accessTtl,
        @NotNull(message = "토큰 유효기간은 필수입니다.") Duration refreshTtl,
        boolean cookieSecure,
        @NotBlank(message = "쿠키 SameSite 설정은 필수입니다.") String cookieSameSite,
        @NotNull(message = "허용 Origin 목록은 필수입니다.") List<String> allowedOrigins
) {
    public AuthProperties {
        if (accessTtl == null || accessTtl.isNegative() || accessTtl.isZero()
                || refreshTtl == null || refreshTtl.compareTo(accessTtl) <= 0) {
            throw new IllegalArgumentException("토큰 유효기간은 양수이며, 재발급 토큰이 더 오래 유효해야 합니다.");
        }
        if (!List.of("Lax", "Strict", "None").contains(cookieSameSite)) {
            throw new IllegalArgumentException("쿠키 SameSite는 Lax, Strict, None 중 하나여야 합니다.");
        }
        if ("None".equals(cookieSameSite) && !cookieSecure) {
            throw new IllegalArgumentException("SameSite=None 쿠키는 HTTPS에서만 사용할 수 있습니다.");
        }
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            var uri = java.net.URI.create(origin);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                throw new IllegalArgumentException("허용할 프론트 주소는 경로 없는 정확한 HTTP·HTTPS Origin이어야 합니다.");
            }
        }
    }
}
