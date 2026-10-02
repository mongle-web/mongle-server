package com.mongle.backend.domain.auth.repository;

import com.mongle.backend.domain.auth.entity.SocialAccount;
import com.mongle.backend.domain.auth.entity.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    Optional<SocialAccount> findByProviderAndProviderUserId(
            SocialProvider provider, String providerUserId);
}
