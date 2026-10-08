package com.mongle.backend.domain.archive.dto.request;

import com.mongle.backend.domain.archive.error.ArchiveErrorCode;
import com.mongle.backend.global.error.BusinessException;
import org.jspecify.annotations.Nullable;

/** 꿈을 꾼 날짜 기준 정렬. 화면에 표시할 제목이나 생성 시각은 정렬 기준에 포함하지 않는다. */
public enum ArchiveSort {
    LATEST,
    OLDEST;

    /** 생략한 요청은 기존 최신순을 유지한다. 잘못된 값은 Archive 도메인의 400 오류로 반환한다. */
    public static ArchiveSort parse(@Nullable String value) {
        if (value == null) return LATEST;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ArchiveErrorCode.INVALID_SORT);
        }
    }
}
