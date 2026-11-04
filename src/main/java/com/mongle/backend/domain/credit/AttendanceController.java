package com.mongle.backend.domain.credit;

import com.mongle.backend.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/attendance", produces = MediaType.APPLICATION_JSON_VALUE)
public class AttendanceController implements AttendanceApi {
    private final AttendanceService attendance;

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<AttendanceStatus>> status(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(attendance.status(Long.valueOf(jwt.getSubject()))));
    }

    @Override
    @PostMapping("/claim")
    public ResponseEntity<ApiResponse<AttendanceClaim>> claim(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(attendance.claim(Long.valueOf(jwt.getSubject()))));
    }
}
