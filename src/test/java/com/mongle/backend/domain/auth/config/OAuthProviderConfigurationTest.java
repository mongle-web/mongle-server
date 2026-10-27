package com.mongle.backend.domain.auth.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

class OAuthProviderConfigurationTest {
    private final OAuthProviderConfiguration config = new OAuthProviderConfiguration();
    private final OAuthProviderProperties.Provider kakao =
            new OAuthProviderProperties.Provider(true, "client", "secret");

    @Test
    void registersOnlyKakaoWithTheExistingAuthorizationContract() {
        var clients = (InMemoryClientRegistrationRepository) config.clientRegistrationRepository(
                new OAuthProviderProperties("https://api.mongle.art", kakao), new MockEnvironment());
        ClientRegistration client = clients.findByRegistrationId("kakao");
        assertThat(clients).extracting(ClientRegistration::getRegistrationId).containsExactly("kakao");
        assertThat(client.getClientAuthenticationMethod().getValue()).isEqualTo("client_secret_post");
        assertThat(client.getAuthorizationGrantType().getValue()).isEqualTo("authorization_code");
        assertThat(client.getRedirectUri()).isEqualTo("https://api.mongle.art/login/oauth2/code/kakao");
        assertThat(client.getScopes()).containsExactly("account_email");
        assertThat(client.getProviderDetails().getAuthorizationUri()).isEqualTo("https://kauth.kakao.com/oauth/authorize");
        assertThat(client.getProviderDetails().getTokenUri()).isEqualTo("https://kauth.kakao.com/oauth/token");
        assertThat(client.getProviderDetails().getUserInfoEndpoint().getUri()).isEqualTo("https://kapi.kakao.com/v2/user/me");
    }

    @Test
    void rejectsMissingDisabledOrUnresolvedKakaoCredentialsWithoutLeakingSecrets() {
        for (var provider : new OAuthProviderProperties.Provider[] {
                null, new OAuthProviderProperties.Provider(false, "ignored", "private-secret"),
                new OAuthProviderProperties.Provider(true, "${missing}", "private-secret"),
                new OAuthProviderProperties.Provider(true, "client", null),
                new OAuthProviderProperties.Provider(true, "client", " "),
                new OAuthProviderProperties.Provider(true, "client\n", "private-secret")}) {
            assertThatThrownBy(() -> config.clientRegistrationRepository(
                    new OAuthProviderProperties("https://api.mongle.art", provider), new MockEnvironment()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("private-secret");
        }
    }

    @Test
    void legacyGoogleConfigurationCannotEnableARegistration() {
        var environment = new MockEnvironment()
                .withProperty("mongle.oauth.base-url", "https://api.mongle.art")
                .withProperty("mongle.oauth.kakao.enabled", "true")
                .withProperty("mongle.oauth.kakao.client-id", "client")
                .withProperty("mongle.oauth.kakao.client-secret", "secret")
                .withProperty("mongle.oauth.google.enabled", "true")
                .withProperty("mongle.oauth.google.client-id", "obsolete")
                .withProperty("mongle.oauth.google.client-secret", "obsolete");
        var properties = Binder.get(environment).bind("mongle.oauth", Bindable.of(OAuthProviderProperties.class)).get();
        var clients = (InMemoryClientRegistrationRepository) config.clientRegistrationRepository(properties, environment);
        assertThat(clients).extracting(ClientRegistration::getRegistrationId).containsExactly("kakao");
        assertThat(clients.findByRegistrationId("google")).isNull();
    }

    @Test
    void requiresAnExactHttpsBackendOriginInProduction() {
        for (String url : new String[] {
                "http://api.mongle.art", "https://api.mongle.art/path",
                "https://user@api.mongle.art", "https://api.mongle.art?x=1", "//api.mongle.art"}) {
            assertThatThrownBy(() -> OAuthProviderConfiguration.baseUrl(url, new MockEnvironment()))
                    .isInstanceOf(IllegalStateException.class);
        }
        var test = new MockEnvironment();
        test.setActiveProfiles("test");
        assertThat(OAuthProviderConfiguration.baseUrl("http://localhost:8080", test)).isEqualTo("http://localhost:8080");
        var mysql = new MockEnvironment();
        mysql.setActiveProfiles("local", "mysql");
        assertThatThrownBy(() -> OAuthProviderConfiguration.baseUrl("http://localhost:8080", mysql))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInsecureOAuthSessionCookiesInProduction() {
        var environment = new MockEnvironment().withProperty("server.servlet.session.cookie.secure", "false");
        environment.setActiveProfiles("mysql", "oauth");
        assertThatThrownBy(() -> config.clientRegistrationRepository(
                new OAuthProviderProperties("https://api.mongle.art", kakao), environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure");
    }
}
