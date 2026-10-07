package com.mongle.backend.domain.auth;

import com.mongle.backend.domain.auth.entity.SocialAccount;
import com.mongle.backend.domain.auth.entity.SocialProvider;
import com.mongle.backend.domain.auth.repository.SocialAccountRepository;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SocialAccountPersistenceTest {

    @Autowired private UserRepository userRepository;
    @Autowired private SocialAccountRepository socialAccountRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void savesPendingUserAndFindsSocialIdentityWithLazyUserRelation() {
        User user = userRepository.saveAndFlush(User.register("verified@example.com"));
        SocialAccount account = socialAccountRepository.saveAndFlush(
                SocialAccount.link(user, SocialProvider.KAKAO, "123456"));
        entityManager.clear();

        SocialAccount reloaded = socialAccountRepository.findByProviderAndProviderUserId(
                SocialProvider.KAKAO, "123456").orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(account.getId());
        assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(reloaded, "user")).isFalse();
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getUser().getNickname()).isNull();
        assertThat(reloaded.getUser().isOnboardingCompleted()).isFalse();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void uniqueIdentityConstraintRejectsSameProviderAndIdEvenForDifferentUsers() {
        User first = userRepository.saveAndFlush(User.register("first@example.com"));
        User second = userRepository.saveAndFlush(User.register("second@example.com"));
        socialAccountRepository.saveAndFlush(SocialAccount.link(first, SocialProvider.GOOGLE, "same-id"));

        assertThatThrownBy(() -> socialAccountRepository.saveAndFlush(
                SocialAccount.link(second, SocialProvider.GOOGLE, "same-id")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameEmailOrSameIdAcrossProvidersDoesNotMergeUsersAndIdIsCaseSensitive() {
        User first = userRepository.saveAndFlush(User.register("same@example.com"));
        User second = userRepository.saveAndFlush(User.register("same@example.com"));
        User third = userRepository.saveAndFlush(User.register("same@example.com"));
        socialAccountRepository.saveAndFlush(SocialAccount.link(first, SocialProvider.GOOGLE, "CaseSensitive"));
        socialAccountRepository.saveAndFlush(SocialAccount.link(second, SocialProvider.KAKAO, "CaseSensitive"));
        socialAccountRepository.saveAndFlush(SocialAccount.link(third, SocialProvider.GOOGLE, "casesensitive"));
        entityManager.clear();

        assertThat(socialAccountRepository.findByProviderAndProviderUserId(
                SocialProvider.GOOGLE, "CaseSensitive").orElseThrow().getUser().getId()).isEqualTo(first.getId());
        assertThat(socialAccountRepository.findByProviderAndProviderUserId(
                SocialProvider.KAKAO, "CaseSensitive").orElseThrow().getUser().getId()).isEqualTo(second.getId());
        assertThat(socialAccountRepository.findByProviderAndProviderUserId(
                SocialProvider.GOOGLE, "casesensitive").orElseThrow().getUser().getId()).isEqualTo(third.getId());
    }

    @Test
    void factoryRejectsMissingOrNonAsciiIdentityBeforePersistence() {
        User user = User.register("verified@example.com");
        assertThatThrownBy(() -> SocialAccount.link(user, SocialProvider.GOOGLE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SocialAccount.link(user, SocialProvider.GOOGLE, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SocialAccount.link(user, SocialProvider.GOOGLE, "한글"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SocialAccount.link(user, SocialProvider.GOOGLE, "a".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
