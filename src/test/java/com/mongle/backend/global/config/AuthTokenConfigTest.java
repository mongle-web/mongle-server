package com.mongle.backend.global.config;

import com.mongle.backend.domain.auth.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AuthTokenConfigTest {
    private final AuthTokenConfig config = new AuthTokenConfig();
    private final AuthProperties properties = properties("", false);
    private final MockEnvironment local = new MockEnvironment();

    AuthTokenConfigTest() { local.setActiveProfiles("local"); }

    @Test
    void validatesSignatureIssuerAudiencePurposeSubjectAndExpiry() {
        var key = config.authSigningKey(properties, local);
        var encoder = config.jwtEncoder(key);
        var decoder = config.jwtDecoder(key, properties);
        Instant now = Instant.now();
        String valid = encode(encoder, "mongle", "mongle-api", "access", "7", now.plusSeconds(900));
        assertThat(decoder.decode(valid).getSubject()).isEqualTo("7");
        for (String invalid : List.of(
                encode(encoder, "other", "mongle-api", "access", "7", now.plusSeconds(900)),
                encode(encoder, "mongle", "other", "access", "7", now.plusSeconds(900)),
                encode(encoder, "mongle", "mongle-api", "refresh", "7", now.plusSeconds(900)),
                encode(encoder, "mongle", "mongle-api", "access", "0", now.plusSeconds(900)),
                encode(encoder, "mongle", "mongle-api", "access", "9223372036854775808", now.plusSeconds(900)),
                encode(encoder, "mongle", "mongle-api", "access", "7", now.minusSeconds(120)))) {
            assertThatThrownBy(() -> decoder.decode(invalid)).isInstanceOf(JwtException.class);
        }
        var otherKey = config.authSigningKey(properties, local);
        assertThatThrownBy(() -> config.jwtDecoder(otherKey, properties).decode(valid)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsMissingProductionSecretShortSecretAndInsecureProductionCookies() {
        var production = new MockEnvironment();
        production.setActiveProfiles("mysql");
        assertThatThrownBy(() -> config.authSigningKey(properties, production))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MONGLE_AUTH_SECRET");
        assertThatThrownBy(() -> config.authSigningKey(properties(Base64.getEncoder().encodeToString(new byte[16]), true), production))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32바이트");
        assertThatThrownBy(() -> config.authSigningKey(properties(Base64.getEncoder().encodeToString(new byte[32]), false), production))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure");
        assertThatThrownBy(() -> new AuthProperties("", "mongle", "mongle-api", Duration.ofMinutes(15), Duration.ofDays(14),
                false, "None", List.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new AuthProperties("", "mongle", "mongle-api", Duration.ofMinutes(15), Duration.ofDays(14),
                true, "Lax", List.of("https://*.example.com"))).isInstanceOf(IllegalArgumentException.class);
    }

    private AuthProperties properties(String secret, boolean secure) {
        return new AuthProperties(secret, "mongle", "mongle-api", Duration.ofMinutes(15), Duration.ofDays(14),
                secure, "Lax", List.of("http://localhost:3000"));
    }

    private String encode(JwtEncoder encoder, String issuer, String audience, String purpose, String subject, Instant expires) {
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(subject)
                .issuedAt(Instant.now().minusSeconds(300)).expiresAt(expires)
                .claim("sid", "1").claim("token_use", purpose).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }
}
