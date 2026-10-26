package com.mongle.backend.domain.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.net.URI;
import java.util.ArrayList;

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
        var clients = new ArrayList<ClientRegistration>();
        var kakao = properties.kakao();
        if (kakao != null && kakao.enabled()) {
            validate(kakao, "KAKAO");
            clients.add(
                    ClientRegistration.withRegistrationId("kakao")
                            .clientId(kakao.clientId())
                            .clientSecret(kakao.clientSecret())
                            .clientAuthenticationMethod(
                                    ClientAuthenticationMethod.CLIENT_SECRET_POST)
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                            .redirectUri(base + "/login/oauth2/code/kakao")
                            .scope("account_email")
                            .clientName("Kakao")
                            .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                            .tokenUri("https://kauth.kakao.com/oauth/token")
                            .userInfoUri("https://kapi.kakao.com/v2/user/me")
                            .userNameAttributeName("id")
                            .build());
        }
        var google = properties.google();
        if (google != null && google.enabled()) {
            validate(google, "GOOGLE");
            clients.add(
                    CommonOAuth2Provider.GOOGLE
                            .getBuilder("google")
                            .clientId(google.clientId())
                            .clientSecret(google.clientSecret())
                            .clientAuthenticationMethod(
                                    ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                            .redirectUri(base + "/login/oauth2/code/google")
                            .scope("openid", "email")
                            .build());
        }
        if (clients.isEmpty()) {
            throw new IllegalStateException("oauth 프로필에는 활성화된 로그인 제공사가 필요합니다.");
        }
        return new InMemoryClientRegistrationRepository(clients);
    }

    private static void validate(OAuthProviderProperties.Provider provider, String name) {
        if (missing(provider.clientId()) || missing(provider.clientSecret())) {
            throw new IllegalStateException(name + " 로그인에 Client ID와 Secret을 설정해야 합니다.");
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
