package com.mongle.backend.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.mongle.backend.domain.user.entity.NicknamePolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record NicknameUpdateRequest(
        @Schema(description = "변경할 닉네임. 한글·영문·숫자 2~10자이며 공백·특수문자는 허용하지 않습니다.", example = "몽글러")
        @NotBlank(message = NicknamePolicy.MESSAGE)
        @Pattern(regexp = NicknamePolicy.REGEX, message = NicknamePolicy.MESSAGE)
        String nickname) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("닉네임만 변경할 수 있습니다.");
    }
}
