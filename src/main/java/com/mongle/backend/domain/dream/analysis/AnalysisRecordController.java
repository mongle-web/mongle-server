package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.global.response.ApiResponse;
import com.mongle.backend.domain.dream.analysis.api.AnalysisRecordApi;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/analysis-records")
public class AnalysisRecordController implements AnalysisRecordApi {
    private final AnalysisRecordService records;
    private final Clock authClock;

    @Override
    @GetMapping("/monthly")
    public ResponseEntity<ApiResponse<AnalysisRecordService.Monthly>> monthly(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String month) {
        YearMonth selected;
        try {
            selected =
                    month == null
                            ? YearMonth.now(authClock.withZone(ZoneId.of("Asia/Seoul")))
                            : YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new com.mongle.backend.global.error.BusinessException(
                    com.mongle.backend.global.error.CommonErrorCode.BAD_REQUEST);
        }
        return ok(records.monthly(Long.valueOf(jwt.getSubject()), selected));
    }

    @Override
    @GetMapping("/recent-context")
    public ResponseEntity<ApiResponse<AnalysisRecordService.Recent>> recent(
            @AuthenticationPrincipal Jwt jwt) {
        return ok(records.recent(Long.valueOf(jwt.getSubject())));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(data));
    }
}
