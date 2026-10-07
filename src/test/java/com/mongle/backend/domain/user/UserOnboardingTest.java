package com.mongle.backend.domain.user;

import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.error.BusinessException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.mongle.backend.domain.user.dto.OnboardingRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserOnboardingTest {

    @Test
    void newSocialUserHasNoInventedNicknameAndNeedsOnboarding() {
        User user = User.register("verified@example.com");
        assertThat(user.getNickname()).isNull();
        assertThat(user.isOnboardingCompleted()).isFalse();
        user.completeOnboarding("몽글이");
        assertThat(user.getNickname()).isEqualTo("몽글이");
        assertThat(user.isOnboardingCompleted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"몽글", "몽글이123", "Mongle", "12", "가나다라마바사아자차", "abcdefghij", "ㄱㄴ"})
    void acceptsNicknameBoundariesAndSupportedCharacters(String nickname) {
        User user = User.register("verified@example.com");
        user.completeOnboarding(nickname);
        assertThat(user.getNickname()).isEqualTo(nickname);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"가", "abcdefghijk", "몽글!", "몽글 이", " 몽글", "몽글 ", "몽글😀", "ab\n", "몽_글", "   "})
    void rejectsInvalidNicknameWithoutChangingUserState(String nickname) {
        User user = User.register("verified@example.com");
        assertThatThrownBy(() -> user.completeOnboarding(nickname))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(UserErrorCode.INVALID_NICKNAME));
        assertThat(user.getNickname()).isNull();
        assertThat(user.isOnboardingCompleted()).isFalse();
    }

    @Test
    void retryOfSameOnboardingIsIdempotentButDifferentNicknameCannotOverwrite() {
        User user = User.register("verified@example.com");
        user.completeOnboarding("몽글이");
        user.completeOnboarding("몽글이");
        assertThatThrownBy(() -> user.completeOnboarding("다른이름"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(UserErrorCode.ONBOARDING_ALREADY_COMPLETED));
        assertThat(user.getNickname()).isEqualTo("몽글이");
    }

    @Test
    void requestValidationRejectsNullAndSpecialCharactersAndAcceptsValidInput() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new OnboardingRequest(null))).isNotEmpty();
            assertThat(validator.validate(new OnboardingRequest("몽글!!!!!"))).isNotEmpty();
            assertThat(validator.validate(new OnboardingRequest("몽글이"))).isEmpty();
        }
    }

    @Test
    void completedCreationCannotBypassNicknamePolicy() {
        assertThatThrownBy(() -> User.create("verified@example.com", "몽글!"))
                .isInstanceOf(BusinessException.class);
        assertThat(User.create("verified@example.com", "몽글").isOnboardingCompleted()).isTrue();
    }
}
