package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.auth.service.SocialLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MongleOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private final SocialIdentityMapper mapper;
    private final SocialLoginService login;
    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        if (!"kakao".equals(request.getClientRegistration().getRegistrationId())) {
            throw SocialIdentityMapper.failure(AuthErrorCode.UNSUPPORTED_PROVIDER);
        }
        OAuth2User providerUser = delegate.loadUser(request);
        SocialIdentity identity = mapper.kakao(providerUser.getAttributes());
        try {
            return new MongleOAuth2User(login.login(identity));
        } catch (DataAccessException failure) {
            throw SocialIdentityMapper.failure(AuthErrorCode.LOGIN_UNAVAILABLE);
        }
    }
}
