package com.mongle.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongle.backend.domain.auth.entity.SocialAccount;
import com.mongle.backend.domain.auth.entity.SocialProvider;
import com.mongle.backend.domain.auth.repository.SocialAccountRepository;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.support.NativeMysqlSupport;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles({"mysql", "oauth"})
@Transactional
class Mysql84KakaoAccountIntegrationTest {
    @Autowired UserRepository users;
    @Autowired SocialAccountRepository accounts;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClientRegistrationRepository registrations;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "credit_test");
        registry.add("mongle.oauth.base-url", () -> "https://api.mongle.art");
        registry.add("mongle.oauth.kakao.enabled", () -> true);
        registry.add("mongle.oauth.kakao.client-id", () -> "native-test-kakao");
        registry.add("mongle.oauth.kakao.client-secret", () -> "native-test-secret");
        registry.add("mongle.auth.allowed-origins", () -> "https://www.mongle.art");
        registry.add("mongle.oauth.web.success-url", () -> "https://www.mongle.art/auth/callback");
        registry.add("mongle.oauth.web.failure-url", () -> "https://www.mongle.art/login");
    }

    @Test
    void existingVarcharProviderSchemaSupportsKakaoOnlyEnumWithoutDdlChanges() throws Exception {
        assertThat(registrations.findByRegistrationId("kakao")).isNotNull();
        assertThat(registrations.findByRegistrationId("google")).isNull();
        var user = users.saveAndFlush(User.register(UUID.randomUUID() + "@example.com"));
        var account = accounts.saveAndFlush(SocialAccount.link(user, SocialProvider.KAKAO, "123456"));
        entityManager.clear();
        assertThat(accounts.findByProviderAndProviderUserId(SocialProvider.KAKAO, "123456")
                .orElseThrow().getId()).isEqualTo(account.getId());
        assertThat(jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.columns WHERE "
                + "table_schema=DATABASE() AND table_name='social_accounts' AND column_name='provider'",
                String.class)).isEqualTo("varchar");
        assertThat(unsupportedCount()).isZero();
    }

    @Test
    void readOnlyPreflightDetectsLegacyAndCaseVariantProviders() throws Exception {
        var user = users.saveAndFlush(User.register(UUID.randomUUID() + "@example.com"));
        int count = unsupportedCount();
        for (String provider : new String[] {"GOOGLE", "kakao", "OTHER"}) {
            jdbc.update("INSERT INTO social_accounts(user_id,provider,provider_user_id,created_at,updated_at) "
                    + "VALUES(?,?,?,NOW(),NOW())", user.getId(), provider,
                    UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            assertThat(unsupportedCount()).isEqualTo(++count);
        }
    }

    private int unsupportedCount() throws Exception {
        String script = new ClassPathResource("db/checks/kakao-only-preflight.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String countSql = script.substring(script.lastIndexOf("SELECT COUNT(*) AS unsupported_provider_count"));
        return jdbc.queryForObject(countSql, Integer.class);
    }
}
