package com.mongle.backend.domain.user;

import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.service.UserService;
import com.mongle.backend.global.error.BusinessException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserServiceTest {

    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private EntityManager entityManager;

    @Test
    void onboardingPersistsChosenNicknameAndSurvivesReload() {
        User user = userRepository.saveAndFlush(User.register("verified@example.com"));
        assertThat(userService.getMe(user.getId()).onboardingCompleted()).isFalse();
        UserResponse result = userService.completeOnboarding(user.getId(), "몽글이");
        entityManager.flush();
        entityManager.clear();

        assertThat(result.onboardingCompleted()).isTrue();
        assertThat(userService.getMe(user.getId()).nickname()).isEqualTo("몽글이");
        assertThat(userService.completeOnboarding(user.getId(), "몽글이").nickname()).isEqualTo("몽글이");
        assertThatThrownBy(() -> userService.completeOnboarding(user.getId(), "다른이름"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(UserErrorCode.ONBOARDING_ALREADY_COMPLETED));
    }

    @Test
    void nicknameDoesNotRequireUniqueness() {
        User first = userRepository.saveAndFlush(User.register("first@example.com"));
        User second = userRepository.saveAndFlush(User.register("second@example.com"));
        userService.completeOnboarding(first.getId(), "몽글이");
        userService.completeOnboarding(second.getId(), "몽글이");
        entityManager.flush();
        assertThat(userService.getMe(first.getId()).nickname())
                .isEqualTo(userService.getMe(second.getId()).nickname());
    }

    @Test
    void missingUserReturnsDomainError() {
        assertThatThrownBy(() -> userService.getMe(Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }
}
