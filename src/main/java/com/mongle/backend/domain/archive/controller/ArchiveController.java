package com.mongle.backend.domain.archive.controller;

import com.mongle.backend.domain.archive.controller.api.ArchiveApi;
import com.mongle.backend.domain.archive.dto.response.ArchiveDetail;
import com.mongle.backend.domain.archive.dto.response.ArchivePage;
import com.mongle.backend.domain.archive.dto.response.DreamCalendarResponse;
import com.mongle.backend.domain.archive.service.ArchiveService;
import com.mongle.backend.global.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** JWT에서 소유자를 결정한다. 사용자 ID를 쿼리로 받지 않아 타인 기록으로 범위를 바꿀 수 없다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/archives")
public class ArchiveController implements ArchiveApi {
    private final ArchiveService archives;

    @Override
    @GetMapping("/calendar")
    public ResponseEntity<ApiResponse<DreamCalendarResponse>> calendar(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String month) {
        // month 생략도 서비스의 월 검증으로 넘겨 형식 오류와 같은 Archive 오류 계약을 적용한다.
        return ok(archives.calendar(Long.valueOf(jwt.getSubject()), month));
    }

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<ArchivePage>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String month, @RequestParam(required = false) String date,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
        return ok(archives.list(Long.valueOf(jwt.getSubject()), month, date, sort, cursor, size));
    }

    @Override
    @GetMapping("/{dreamId}")
    public ResponseEntity<ApiResponse<ArchiveDetail>> detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId) {
        return ok(archives.detail(Long.valueOf(jwt.getSubject()), dreamId));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        // 원문과 사적인 꿈 정보를 브라우저·공유 프록시 캐시에 남기지 않는다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }
}
