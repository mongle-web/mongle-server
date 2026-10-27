package com.mongle.backend.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mongle.oauth")
public record OAuthProviderProperties(String baseUrl, Provider kakao) {
    public record Provider(boolean enabled, String clientId, String clientSecret) {}
}
