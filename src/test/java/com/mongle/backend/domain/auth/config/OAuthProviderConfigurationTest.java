package com.mongle.backend.domain.auth.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OAuthProviderConfigurationTest {
    private final OAuthProviderConfiguration config = new OAuthProviderConfiguration();
    private final OAuthProviderProperties.Provider kakao =
            new OAuthProviderProperties.Provider(true, "client", "secret");

    @Test
    void onlyEnabledProvidersNeedCredentials() {
        var disabled = new OAuthProviderProperties.Provider(false, "${missing}", null);
        var clients =
                config.clientRegistrationRepository(
                        new OAuthProviderProperties("https://api.mongle.art", kakao, disabled),
                        new MockEnvironment());
        assertThat(clients.findByRegistrationId("kakao").getClientAuthenticationMethod().getValue())
                .isEqualTo("client_secret_post");
        assertThat(clients.findByRegistrationId("google")).isNull();
        assertThatThrownBy(
                        () ->
                                config.clientRegistrationRepository(
                                        new OAuthProviderProperties(
                                                "https://api.mongle.art", disabled, disabled),
                                        new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
        var missing = new OAuthProviderProperties.Provider(true, "${missing}", "private-secret");
        assertThatThrownBy(
                        () ->
                                config.clientRegistrationRepository(
                                        new OAuthProviderProperties(
                                                "https://api.mongle.art", missing, disabled),
                                        new MockEnvironment()))
                .hasMessageContaining("KAKAO")
                .hasMessageNotContaining("private-secret");
    }

    @Test
    void requiresAnExactHttpsBackendOriginInProduction() {
        for (String url :
                new String[] {
                    "http://api.mongle.art",
                    "https://api.mongle.art/path",
                    "https://user@api.mongle.art",
                    "https://api.mongle.art?x=1",
                    "//api.mongle.art"
                }) {
            assertThatThrownBy(() -> OAuthProviderConfiguration.baseUrl(url, new MockEnvironment()))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(
                        OAuthProviderConfiguration.baseUrl(
                                "http://localhost:8080",
                                new MockEnvironment()
                                        .withProperty("spring.profiles.active", "test")))
                .isEqualTo("http://localhost:8080");
        var mysql = new MockEnvironment();
        mysql.setActiveProfiles("local", "mysql");
        assertThatThrownBy(() -> OAuthProviderConfiguration.baseUrl("http://localhost:8080", mysql))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInsecureOAuthSessionCookiesInProduction() {
        var environment =
                new MockEnvironment().withProperty("server.servlet.session.cookie.secure", "false");
        environment.setActiveProfiles("mysql", "oauth");
        assertThatThrownBy(
                        () ->
                                config.clientRegistrationRepository(
                                        new OAuthProviderProperties(
                                                "https://api.mongle.art", kakao, null),
                                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Secure");
    }
}
