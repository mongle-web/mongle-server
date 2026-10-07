package com.mongle.backend.domain.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthCookieConfigTest {
    @Test
    void sessionCookieIsSecureUnlessExplicitlyDisabledForLocalHttp() throws Exception {
        var environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("oauth", new ClassPathResource("application-oauth.yaml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        environment.setProperty("OAUTH_COOKIE_SECURE", "false");
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isFalse();
        environment.setProperty("OAUTH_COOKIE_SECURE", "true");
        assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
    }
}
