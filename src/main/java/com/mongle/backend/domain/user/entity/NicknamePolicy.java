package com.mongle.backend.domain.user.entity;

import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.error.BusinessException;

import java.util.regex.Pattern;

public final class NicknamePolicy {

    public static final String REGEX = "^[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]{2,10}$";
    public static final String MESSAGE = "닉네임은 한글, 영문, 숫자로 2~10자 입력해주세요.";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private NicknamePolicy() {
    }

    public static String validate(String nickname) {
        if (nickname == null || !PATTERN.matcher(nickname).matches()) {
            throw new BusinessException(UserErrorCode.INVALID_NICKNAME);
        }
        return nickname;
    }
}
