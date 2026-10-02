package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.auth.entity.SocialProvider;

/** 검증을 통과한 소셜 로그인 응답에서 계정 식별 정보를 만든다. */
public record SocialIdentity(SocialProvider provider, String providerUserId, String email) {}
