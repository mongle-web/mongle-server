package com.mongle.backend.domain.user.dto;

import com.mongle.backend.domain.user.entity.NicknamePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OnboardingRequest(
        @NotBlank(message = NicknamePolicy.MESSAGE)
        @Pattern(regexp = NicknamePolicy.REGEX, message = NicknamePolicy.MESSAGE)
        String nickname
) {
}
