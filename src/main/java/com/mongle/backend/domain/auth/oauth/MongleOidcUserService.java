package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.auth.service.SocialLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MongleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private final SocialIdentityMapper mapper;
    private final SocialLoginService login;
    private final OidcUserService delegate = new OidcUserService();

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
            throw SocialIdentityMapper.failure(AuthErrorCode.UNSUPPORTED_PROVIDER);
        }
        // Spring Security가 ID 토큰의 서명, 발급자, 수신 대상, nonce를 검증한다.
        OidcUser providerUser = delegate.loadUser(request);
        SocialIdentity identity = mapper.google(providerUser.getIdToken().getClaims());
        try {
            return new MongleOidcUser(providerUser, login.login(identity));
        } catch (DataAccessException failure) {
            throw SocialIdentityMapper.failure(AuthErrorCode.LOGIN_UNAVAILABLE);
        }
    }
}
