package com.mongle.backend.domain.archive.dto.request;

import com.mongle.backend.domain.archive.error.ArchiveErrorCode;
import com.mongle.backend.global.error.BusinessException;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Base64;

/**
 * HTTP 조회 조건을 검증한 불변 값. 날짜·ID는 쿼리 파라미터로 바인딩하고 쿼리 문장에 삽입하지 않는다.
 * 커서는 마지막 정렬 위치이며 권한 토큰이 아니다. 실제 소유자는 인증된 사용자로만 제한한다.
 */
public record ArchiveSearch(@Nullable LocalDate from, @Nullable LocalDate to, String scope,
                            int size, @Nullable Position position) {
    /** 날짜 내림차순, 같은 날짜의 ID 내림차순 정렬에서 다음 페이지의 시작 경계다. */
    public record Position(LocalDate dreamedAt, long dreamId) {}

    /** 월·일 조건은 서로 배타적이다. 둘 다 생략하면 내 완성 기록 전체를 조회한다. */
    public static ArchiveSearch parse(@Nullable String month, @Nullable String date,
                                      @Nullable String cursor, int size) {
        if (size < 1 || size > 50) throw new BusinessException(ArchiveErrorCode.INVALID_SIZE);
        LocalDate from = null;
        LocalDate to = null;
        String scope = "all";
        try {
            if (month != null && date != null) throw new IllegalArgumentException();
            if (month != null) {
                if (!month.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
                var selected = YearMonth.parse(month);
                if (selected.getYear() < 1) throw new IllegalArgumentException();
                from = selected.atDay(1);
                to = selected.atEndOfMonth();
                scope = "month:" + month;
            } else if (date != null) {
                from = parseDate(date);
                to = from;
                scope = "date:" + date;
            }
        } catch (RuntimeException exception) {
            throw new BusinessException(ArchiveErrorCode.INVALID_FILTER);
        }
        // 다른 월·날짜의 커서를 재사용하면 누락처럼 보일 수 있어 원래 필터와 같은지 검사한다.
        Position position = cursor == null ? null : decode(cursor, scope, from, to);
        return new ArchiveSearch(from, to, scope, size, position);
    }

    /** 응답에 포함한 마지막 카드만 경계로 사용한다. size+1로 읽은 확인용 행은 경계에 넣지 않는다. */
    public String cursorAfter(LocalDate date, long id) {
        String raw = "v1|" + scope + "|" + date + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String cursor, String scope, @Nullable LocalDate from, @Nullable LocalDate to) {
        try {
            // 클라이언트 입력을 무제한 디코딩하지 않는다. 커서는 서버가 발급한 URL-safe 형식만 허용한다.
            if (cursor.isEmpty() || cursor.length() > 128) throw new IllegalArgumentException();
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(cursor))
                throw new IllegalArgumentException();
            String[] fields = new String(decoded, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 4 || !fields[0].equals("v1") || !fields[1].equals(scope))
                throw new IllegalArgumentException();
            LocalDate date = parseDate(fields[2]);
            long id = Long.parseLong(fields[3]);
            if (id < 1 || (from != null && (date.isBefore(from) || date.isAfter(to))))
                throw new IllegalArgumentException();
            return new Position(date, id);
        } catch (RuntimeException exception) {
            throw new BusinessException(ArchiveErrorCode.INVALID_CURSOR);
        }
    }

    private static LocalDate parseDate(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
        var date = LocalDate.parse(value);
        if (date.getYear() < 1) throw new IllegalArgumentException();
        return date;
    }
}
