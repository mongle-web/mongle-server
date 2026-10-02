package com.mongle.backend.domain.user.controller;

import com.mongle.backend.domain.user.controller.api.UserApi;
import com.mongle.backend.domain.user.dto.OnboardingRequest;
import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.domain.user.service.UserService;
import com.mongle.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/users/me", produces = MediaType.APPLICATION_JSON_VALUE)
public class UserController implements UserApi {
    private final UserService users;

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<UserResponse>> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(users.getMe(Long.valueOf(jwt.getSubject()))));
    }

    @Override
    @PostMapping("/onboarding")
    public ResponseEntity<ApiResponse<UserResponse>> onboarding(@AuthenticationPrincipal Jwt jwt,
                                                               @Valid @RequestBody OnboardingRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                users.completeOnboarding(Long.valueOf(jwt.getSubject()), request.nickname())));
    }
}
