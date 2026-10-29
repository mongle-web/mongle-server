package com.mongle.backend.domain.archive.dto.request;

import com.mongle.backend.domain.archive.error.ArchiveErrorCode;
import com.mongle.backend.global.common.SearchQuery;
import com.mongle.backend.global.error.BusinessException;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HTTP 조회 조건을 검증한 불변 값. 날짜·ID는 쿼리 파라미터로 바인딩하고 쿼리 문장에 삽입하지 않는다.
 * 커서는 마지막 정렬 위치이며 권한 토큰이 아니다. 실제 소유자는 인증된 사용자로만 제한한다.
 */
public record ArchiveSearch(@Nullable LocalDate from, @Nullable LocalDate to, String scope,
                            ArchiveSort sort, int size, @Nullable Position position, String query) {
    /** 선택한 날짜·ID 정렬에서 다음 페이지의 시작 경계다. 삭제된 기록도 위치 값으로 사용할 수 있다. */
    public record Position(LocalDate dreamedAt, long dreamId) {}

    /** 월·일 조건은 서로 배타적이다. 둘 다 생략하면 내 완성 기록 전체를 조회한다. */
    public static ArchiveSearch parse(@Nullable String month, @Nullable String date,
                                      @Nullable String sortValue, @Nullable String cursor, int size,
                                      @Nullable String queryValue) {
        if (size < 1 || size > 50) throw new BusinessException(ArchiveErrorCode.INVALID_SIZE);
        String query = SearchQuery.normalize(queryValue, ArchiveErrorCode.INVALID_QUERY);
        ArchiveSort sort = ArchiveSort.parse(sortValue);
        LocalDate from = null;
        LocalDate to = null;
        String scope = "all";
        try {
            if (month != null && date != null) throw new IllegalArgumentException();
            if (month != null) {
                // 캘린더와 같은 월 검증을 사용해 목록과 달력의 조회 범위가 어긋나지 않게 한다.
                var selected = ArchiveMonth.parse(month);
                from = selected.firstDay();
                to = selected.lastDay();
                scope = "month:" + month;
            } else if (date != null) {
                from = parseDate(date);
                to = from;
                scope = "date:" + date;
            }
        } catch (RuntimeException exception) {
            throw new BusinessException(ArchiveErrorCode.INVALID_FILTER);
        }
        // 검색어도 경계의 조건이다. 원문 검색어 대신 정규화한 검색어의 해시를 커서에 넣는다.
        Position position = cursor == null ? null : decode(cursor, scope, sort, from, to, query);
        return new ArchiveSearch(from, to, scope, sort, size, position, query);
    }

    /** 응답에 포함한 마지막 카드만 경계로 사용한다. size+1로 읽은 확인용 행은 경계에 넣지 않는다. */
    public String cursorAfter(LocalDate date, long id) {
        String raw = query.isEmpty()
                ? "v2|" + scope + "|" + sort.name() + "|" + date + "|" + id
                : "v3|" + scope + "|" + sort.name() + "|" + fingerprint(query) + "|" + date + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String cursor, String scope, ArchiveSort sort,
                                   @Nullable LocalDate from, @Nullable LocalDate to, String query) {
        try {
            // 클라이언트 입력을 무제한 디코딩하지 않는다. 커서는 서버가 발급한 URL-safe 형식만 허용한다.
            if (cursor.isEmpty() || cursor.length() > 256) throw new IllegalArgumentException();
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(cursor))
                throw new IllegalArgumentException();
            String[] fields = new String(decoded, StandardCharsets.UTF_8).split("\\|", -1);
            // 기존 v1 커서는 최신순으로만 발급됐으므로 LATEST에서만 호환한다.
            // 새 v2 커서는 정렬을 명시해 최신순과 오래된순 사이의 잘못된 재사용을 차단한다.
            boolean legacy = fields.length == 4 && fields[0].equals("v1");
            boolean searched = fields.length == 6 && fields[0].equals("v3");
            int dateIndex;
            if (searched) {
                if (query.isEmpty() || !fields[1].equals(scope) || !fields[2].equals(sort.name())
                        || !fields[3].equals(fingerprint(query))) throw new IllegalArgumentException();
                dateIndex = 4;
            } else if (legacy) {
                if (!query.isEmpty()) throw new IllegalArgumentException();
                if (sort != ArchiveSort.LATEST || !fields[1].equals(scope)) throw new IllegalArgumentException();
                dateIndex = 2;
            } else {
                if (!query.isEmpty() || fields.length != 5 || !fields[0].equals("v2")
                        || !fields[1].equals(scope) || !fields[2].equals(sort.name()))
                    throw new IllegalArgumentException();
                dateIndex = 3;
            }
            LocalDate date = parseDate(fields[dateIndex]);
            long id = Long.parseLong(fields[dateIndex + 1]);
            if (id < 1 || (from != null && (date.isBefore(from) || date.isAfter(to))))
                throw new IllegalArgumentException();
            return new Position(date, id);
        } catch (RuntimeException exception) {
            throw new BusinessException(ArchiveErrorCode.INVALID_CURSOR);
        }
    }

    private static String fingerprint(String query) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(query.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static LocalDate parseDate(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
        var date = LocalDate.parse(value);
        if (date.getYear() < 1) throw new IllegalArgumentException();
        return date;
    }
}
