package com.mongle.backend.domain.auth;

import com.mongle.backend.domain.auth.entity.SocialProvider;
import com.mongle.backend.domain.auth.oauth.SocialIdentityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialIdentityMapperTest {
    private final SocialIdentityMapper mapper = new SocialIdentityMapper();

    @Test
    void googleUsesCaseSensitiveSubjectAndIgnoresProfileNickname() {
        var identity = mapper.google(Map.of("sub", "Google-A", "email", "user@example.com",
                "email_verified", true, "name", "제공사닉네임"));
        assertThat(identity.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(identity.providerUserId()).isEqualTo("Google-A");
        assertThat(identity.email()).isEqualTo("user@example.com");
    }

    @Test
    void rejectsMissingFalseAndStringEmailVerification() {
        for (Object flag : new Object[]{false, "true", 1}) {
            assertCode(() -> mapper.google(Map.of("sub", "id", "email", "user@example.com",
                    "email_verified", flag)), "AUTH_EMAIL_NOT_VERIFIED");
        }
        assertCode(() -> mapper.google(Map.of("sub", "id", "email", "user@example.com")),
                "AUTH_EMAIL_NOT_VERIFIED");
    }

    @Test
    void distinguishesMissingEmailFromMalformedIdentity() {
        assertCode(() -> mapper.google(Map.of("sub", "id", "email_verified", true)),
                "AUTH_EMAIL_REQUIRED");
        assertCode(() -> mapper.google(Map.of("sub", "id", "email", "invalid", "email_verified", true)),
                "AUTH_INVALID_PROVIDER_RESPONSE");
        assertCode(() -> mapper.google(Map.of("sub", " bad id", "email", "user@example.com",
                "email_verified", true)), "AUTH_INVALID_PROVIDER_RESPONSE");
    }

    @Test
    void kakaoRequiresBothValidityAndVerificationAndPositiveIntegerId() {
        var account = new HashMap<String, Object>();
        account.put("email", "user@example.com");
        account.put("is_email_valid", true);
        account.put("is_email_verified", true);
        assertThat(mapper.kakao(Map.of("id", 1234567890123L, "kakao_account", account)).providerUserId())
                .isEqualTo("1234567890123");
        account.remove("is_email_valid");
        assertCode(() -> mapper.kakao(Map.of("id", 123L, "kakao_account", account)),
                "AUTH_EMAIL_NOT_VERIFIED");
        account.put("is_email_valid", true);
        account.put("is_email_verified", false);
        assertCode(() -> mapper.kakao(Map.of("id", 123L, "kakao_account", account)),
                "AUTH_EMAIL_NOT_VERIFIED");
        for (Object id : new Object[]{-1L, 0, 1.5, "123"}) {
            assertCode(() -> mapper.kakao(Map.of("id", id, "kakao_account", account)),
                    "AUTH_INVALID_PROVIDER_RESPONSE");
        }
    }

    private void assertCode(Runnable task, String code) {
        assertThatThrownBy(task::run).isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                failure -> assertThat(failure.getError().getErrorCode()).isEqualTo(code));
    }
}
