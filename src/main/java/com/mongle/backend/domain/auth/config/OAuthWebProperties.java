package com.mongle.backend.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mongle.oauth.web")
public record OAuthWebProperties(String successUrl, String failureUrl) {}
