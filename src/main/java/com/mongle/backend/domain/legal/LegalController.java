package com.mongle.backend.domain.legal;

import com.mongle.backend.domain.user.dto.UserResponse;
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
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class LegalController implements LegalApi {
    private final LegalService legal;

    @Override
    @GetMapping("/api/v1/legal/documents")
    public ResponseEntity<ApiResponse<LegalService.Catalog>> documents() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(legal.catalog()));
    }

    @Override
    @PostMapping(value = "/api/v1/users/me/agreements", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<UserResponse>> accept(
            @AuthenticationPrincipal Jwt jwt, @RequestBody LegalService.Accept request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(legal.accept(Long.valueOf(jwt.getSubject()), request)));
    }
}
