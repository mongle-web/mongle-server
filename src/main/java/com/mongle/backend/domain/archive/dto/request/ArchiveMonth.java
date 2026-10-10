package com.mongle.backend.domain.archive.dto.request;

import com.mongle.backend.domain.archive.error.ArchiveErrorCode;
import com.mongle.backend.global.error.BusinessException;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.YearMonth;

/** Archive 목록과 캘린더에서 같은 월 형식과 날짜 범위를 사용하기 위한 불변 조회 조건. */
public record ArchiveMonth(YearMonth value) {
    /** 월은 생략할 수 없다. 네 자리 양수 연도와 두 자리 월만 허용해 입력 형식을 일관되게 유지한다. */
    public static ArchiveMonth parse(@Nullable String month) {
        try {
            if (month == null || !month.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
            YearMonth value = YearMonth.parse(month);
            if (value.getYear() < 1) throw new IllegalArgumentException();
            return new ArchiveMonth(value);
        } catch (RuntimeException exception) {
            throw new BusinessException(ArchiveErrorCode.INVALID_FILTER);
        }
    }

    /** 저장된 dreamedAt과 비교할 첫날이다. 생성 시각이나 서버 시간대는 조회 범위에 영향을 주지 않는다. */
    public LocalDate firstDay() {
        return value.atDay(1);
    }

    /** 월 길이와 윤년을 반영한 마지막 날이다. 별도의 월별 일수 표를 유지하지 않는다. */
    public LocalDate lastDay() {
        return value.atEndOfMonth();
    }
}
