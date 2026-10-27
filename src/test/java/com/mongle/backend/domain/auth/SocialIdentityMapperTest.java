package com.mongle.backend.domain.auth;

import com.mongle.backend.domain.auth.entity.SocialProvider;
import com.mongle.backend.domain.auth.oauth.SocialIdentityMapper;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialIdentityMapperTest {
    private final SocialIdentityMapper mapper = new SocialIdentityMapper();

    @Test
    void kakaoUsesAnExactPositiveIntegerIdentityAndIgnoresProfileNickname() {
        for (Number id : new Number[] {(byte)1, (short)123, 123456, 1234567890123L,
                new BigInteger("9223372036854775808")}) {
            var identity = mapper.kakao(Map.of("id", id, "kakao_account", account(),
                    "properties", Map.of("nickname", "제공사닉네임")));
            assertThat(identity.provider()).isEqualTo(SocialProvider.KAKAO);
            assertThat(identity.providerUserId()).isEqualTo(id.toString());
            assertThat(identity.email()).isEqualTo("user@example.com");
        }
    }

    @Test
    void rejectsMissingFalseAndNonBooleanEmailValidityOrVerification() {
        for (String key : new String[] {"is_email_valid", "is_email_verified"}) {
            for (Object flag : new Object[] {false, "true", 1}) {
                var account = account(); account.put(key, flag);
                assertCode(() -> mapper.kakao(Map.of("id", 123L, "kakao_account", account)), "AUTH_401_3");
            }
            var account = account(); account.remove(key);
            assertCode(() -> mapper.kakao(Map.of("id", 123L, "kakao_account", account)), "AUTH_401_3");
        }
    }

    @Test
    void distinguishesMissingEmailFromMalformedEmailOrAccount() {
        assertCode(() -> mapper.kakao(Map.of("id", 123L)), "AUTH_401_2");
        assertCode(() -> mapper.kakao(Map.of("id", 123L, "kakao_account", "invalid")), "AUTH_401_2");
        for (Object email : new Object[] {"", " ", 123}) {
            var account = account(); account.put("email", email);
            assertCode(() -> mapper.kakao(Map.of("id", 123L, "kakao_account", account)), "AUTH_401_2");
        }
        var missing = account(); missing.remove("email");
        assertCode(() -> mapper.kakao(Map.of("id", 123L, "kakao_account", missing)), "AUTH_401_2");
        for (String email : new String[] {"invalid", "bad address@example.com", "a".repeat(256) + "@example.com"}) {
            var account = account(); account.put("email", email);
            assertCode(() -> mapper.kakao(Map.of("id", 123L, "kakao_account", account)), "AUTH_401_1");
        }
    }

    @Test
    void rejectsMissingNonIntegerNonPositiveAndOversizedKakaoIds() {
        assertCode(() -> mapper.kakao(Map.of("kakao_account", account())), "AUTH_401_1");
        for (Object id : new Object[] {-1L, 0, 1.5, 1.0f, "123", true, BigInteger.TEN.pow(255)}) {
            assertCode(() -> mapper.kakao(Map.of("id", id, "kakao_account", account())), "AUTH_401_1");
        }
    }

    private HashMap<String, Object> account() {
        return new HashMap<>(Map.of("email", "user@example.com", "is_email_valid", true, "is_email_verified", true));
    }

    private void assertCode(Runnable task, String code) {
        assertThatThrownBy(task::run).isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                failure -> assertThat(failure.getError().getErrorCode()).isEqualTo(code));
    }
}
