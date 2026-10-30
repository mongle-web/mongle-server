package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.dream.image.api.ImageDownloadApi;
import com.mongle.backend.global.response.ApiResponse;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/images")
public class ImageDownloadController implements ImageDownloadApi {
    private final ImageDownloadService service;

    @Override
    @PostMapping("/download-urls")
    public ResponseEntity<ApiResponse<ImageDownloadResponse>> downloadUrls(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ImageDownloadRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.success(
                                service.downloadUrls(
                                        Long.valueOf(jwt.getSubject()), request.imageIds())));
    }
}
