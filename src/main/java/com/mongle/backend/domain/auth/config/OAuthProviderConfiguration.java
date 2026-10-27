package com.mongle.backend.domain.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@Profile("oauth")
@EnableConfigurationProperties(OAuthProviderProperties.class)
public class OAuthProviderConfiguration {
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(
            OAuthProviderProperties properties, Environment environment) {
        String base = baseUrl(properties.baseUrl(), environment);
        boolean local =
                environment.acceptsProfiles(Profiles.of("local", "test"))
                        && !environment.acceptsProfiles(Profiles.of("mysql", "prod"));
        if (!local
                && !environment.getProperty(
                        "server.servlet.session.cookie.secure", Boolean.class, true)) {
            throw new IllegalStateException("운영 OAuth 세션 쿠키는 Secure를 활성화해야 합니다.");
        }
        var kakao = properties.kakao();
        if (kakao == null || !kakao.enabled()) {
            throw new IllegalStateException("oauth 프로필에는 카카오 로그인을 활성화해야 합니다.");
        }
        validate(kakao);
        var client = ClientRegistration.withRegistrationId("kakao")
                .clientId(kakao.clientId())
                .clientSecret(kakao.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(base + "/login/oauth2/code/kakao")
                .scope("account_email")
                .clientName("Kakao")
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .userInfoUri("https://kapi.kakao.com/v2/user/me")
                .userNameAttributeName("id")
                .build();
        return new InMemoryClientRegistrationRepository(client);
    }

    private static void validate(OAuthProviderProperties.Provider provider) {
        if (missing(provider.clientId()) || missing(provider.clientSecret())) {
            throw new IllegalStateException("KAKAO 로그인에 Client ID와 Secret을 설정해야 합니다.");
        }
    }

    private static boolean missing(String value) {
        return value == null
                || value.isBlank()
                || value.contains("${")
                || value.chars().anyMatch(Character::isISOControl);
    }

    static String baseUrl(String value, Environment environment) {
        try {
            URI uri = URI.create(value);
            boolean local =
                    environment.acceptsProfiles(Profiles.of("local", "test"))
                            && !environment.acceptsProfiles(Profiles.of("mysql", "prod"));
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || !("https".equals(uri.getScheme())
                            || (local && "http".equals(uri.getScheme())))) {
                throw new IllegalArgumentException();
            }
            return uri.toASCIIString();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("OAUTH_BASE_URL은 경로 없는 운영 HTTPS Origin이어야 합니다.");
        }
    }
}
