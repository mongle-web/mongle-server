package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.dream.story.api.StoryApi;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.response.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class StoryController implements StoryApi {

    private final DreamStoryService service;
    private final StoryTransactions transactions;

    @Override
    @PostMapping("/dreams/{dreamId}/story")
    public CompletableFuture<ResponseEntity<ApiResponse<StoryResponse>>> generate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long dreamId,
            @Valid @RequestBody StoryRequest request) {
        return service.generate(Long.valueOf(jwt.getSubject()), dreamId, request)
                .thenApply(
                        result -> {
                            var status =
                                    result.status() == GenerationStatus.PROCESSING
                                            ? HttpStatus.ACCEPTED
                                            : HttpStatus.OK;

                            return ResponseEntity.status(status)
                                    .cacheControl(CacheControl.noStore())
                                    .body(ApiResponse.success(result));
                        });
    }

    @Override
    @GetMapping("/dreams/{dreamId}/story")
    public ResponseEntity<ApiResponse<StoryResponse>> latest(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long dreamId) {
        var result = transactions.latest(Long.valueOf(jwt.getSubject()), dreamId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result));
    }

    @Override
    @GetMapping("/stories/{storyId}")
    public ResponseEntity<ApiResponse<StoryResponse>> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long storyId) {
        var result = transactions.get(Long.valueOf(jwt.getSubject()), storyId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result));
    }
}
