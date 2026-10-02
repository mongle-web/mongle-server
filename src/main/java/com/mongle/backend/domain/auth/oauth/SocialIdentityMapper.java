package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.auth.entity.SocialProvider;
import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class SocialIdentityMapper {
    private static final Pattern PROVIDER_ID = Pattern.compile("[\\x21-\\x7E]{1,255}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    public SocialIdentity google(Map<String, Object> idTokenClaims) {
        Object sub = idTokenClaims.get("sub");
        if (!(sub instanceof String id) || !PROVIDER_ID.matcher(id).matches()) {
            throw failure(AuthErrorCode.INVALID_PROVIDER_RESPONSE);
        }
        String email = email(idTokenClaims.get("email"));
        if (!Boolean.TRUE.equals(idTokenClaims.get("email_verified"))) {
            throw failure(AuthErrorCode.EMAIL_NOT_VERIFIED);
        }
        return new SocialIdentity(SocialProvider.GOOGLE, id, email);
    }

    public SocialIdentity kakao(Map<String, Object> attributes) {
        Object rawId = attributes.get("id");
        // 카카오 사용자 ID는 프로필 항목이 아니라 응답의 양수 정수 값이다.
        if (!(rawId instanceof Byte || rawId instanceof Short || rawId instanceof Integer
                || rawId instanceof Long || rawId instanceof BigInteger)) {
            throw failure(AuthErrorCode.INVALID_PROVIDER_RESPONSE);
        }
        String id = rawId.toString();
        if (new BigInteger(id).signum() <= 0 || id.length() > 255) {
            throw failure(AuthErrorCode.INVALID_PROVIDER_RESPONSE);
        }
        if (!(attributes.get("kakao_account") instanceof Map<?, ?> account)) {
            throw failure(AuthErrorCode.EMAIL_REQUIRED);
        }
        String email = email(account.get("email"));
        if (!Boolean.TRUE.equals(account.get("is_email_valid"))
                || !Boolean.TRUE.equals(account.get("is_email_verified"))) {
            throw failure(AuthErrorCode.EMAIL_NOT_VERIFIED);
        }
        return new SocialIdentity(SocialProvider.KAKAO, id, email);
    }

    private String email(Object value) {
        if (!(value instanceof String email) || email.isBlank()) {
            throw failure(AuthErrorCode.EMAIL_REQUIRED);
        }
        if (email.length() > 255 || !EMAIL.matcher(email).matches()) {
            throw failure(AuthErrorCode.INVALID_PROVIDER_RESPONSE);
        }
        return email;
    }

    public static OAuth2AuthenticationException failure(AuthErrorCode code) {
        return new OAuth2AuthenticationException(new OAuth2Error(code.getCode()), code.getMessage());
    }
}
