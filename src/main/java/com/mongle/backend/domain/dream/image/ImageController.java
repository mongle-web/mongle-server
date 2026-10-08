package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.dream.image.api.ImageApi;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.response.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ImageController implements ImageApi {
    private final DreamImageService service;
    private final ImageTransactions transactions;
    private final ImageOptions options;

    @Override
    @GetMapping("/image-options")
    public ResponseEntity<ApiResponse<ImageOptions>> options(@AuthenticationPrincipal Jwt jwt) {
        return ok(options);
    }

    @Override
    @PostMapping("/dreams/{dreamId}/image")
    public CompletableFuture<ResponseEntity<ApiResponse<ImageResponse>>> generate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long dreamId,
            @Valid @RequestBody ImageRequest request) {
        return externalBoundary(
                        () -> service.generate(Long.valueOf(jwt.getSubject()), dreamId, request))
                .handle((result, failure) -> {
                    if (failure != null) {
                        while (failure instanceof CompletionException && failure.getCause() != null) {
                            failure = failure.getCause();
                        }
                        if (failure instanceof BusinessException business) {
                            throw new BusinessException(business.getErrorCode());
                        }
                        throw new BusinessException(ImageErrorCode.CALL_FAILED);
                    }
                    return ResponseEntity.status(
                                    result.status() == GenerationStatus.PROCESSING
                                            ? HttpStatus.ACCEPTED : HttpStatus.OK)
                            .cacheControl(CacheControl.noStore())
                            .body(ApiResponse.success(result));
                });
    }

    @Override
    @GetMapping("/dreams/{dreamId}/image")
    public ResponseEntity<ApiResponse<ImageResponse>> latest(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long dreamId) {
        return ok(transactions.latest(Long.valueOf(jwt.getSubject()), dreamId));
    }

    @Override
    @GetMapping("/images/{imageId}")
    public ResponseEntity<ApiResponse<ImageResponse>> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long imageId) {
        return ok(transactions.get(Long.valueOf(jwt.getSubject()), imageId));
    }

    @Override
    @GetMapping("/images/{imageId}/download-url")
    public ResponseEntity<ApiResponse<ImageAssetStore.DownloadUrl>> downloadUrl(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long imageId) {
        return ok(
                externalBoundary(
                        () -> service.downloadUrl(Long.valueOf(jwt.getSubject()), imageId)));
    }

    // 서비스는 원인/보상 실패를 보존한다. 공통 예외 로그에는 외부 URL·키·응답을 전달하지 않는다.
    private static <T> T externalBoundary(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (BusinessException ex) {
            if (ex.getCause() != null) throw new BusinessException(ex.getErrorCode());
            throw ex;
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T result) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result));
    }
}
