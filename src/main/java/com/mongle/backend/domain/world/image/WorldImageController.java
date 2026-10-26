package com.mongle.backend.domain.world.image;

import com.mongle.backend.domain.dream.image.*;
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
import java.util.concurrent.*;
import java.util.function.Supplier;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/worlds/{worldId}/image")
public class WorldImageController implements WorldImageApi {
    private final WorldImageService service;
    private final WorldImageTransactions transactions;
    @Override @PostMapping
    public CompletableFuture<ResponseEntity<ApiResponse<WorldImageResponse>>> generate(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long worldId,@Valid @RequestBody WorldImageRequest request) {
        return boundary(()->service.generate(Long.valueOf(jwt.getSubject()),worldId,request)).handle((result,failure)->{
            if(failure!=null) {
                while(failure instanceof CompletionException && failure.getCause()!=null) failure=failure.getCause();
                if(failure instanceof BusinessException b) throw new BusinessException(b.getErrorCode());
                throw new BusinessException(ImageErrorCode.CALL_FAILED);
            }
            return ResponseEntity.status(result.status()==GenerationStatus.PROCESSING?HttpStatus.ACCEPTED:HttpStatus.OK)
                    .cacheControl(CacheControl.noStore()).body(ApiResponse.success(result));
        });
    }
    @Override @GetMapping
    public ResponseEntity<ApiResponse<WorldImageResponse>> get(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive Long worldId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(transactions.latest(Long.valueOf(jwt.getSubject()),worldId)));
    }
    @Override @GetMapping("/download-url")
    public ResponseEntity<ApiResponse<ImageAssetStore.DownloadUrl>> download(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive Long worldId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(boundary(()->service.downloadUrl(Long.valueOf(jwt.getSubject()),worldId))));
    }
    private static <T> T boundary(Supplier<T> action) {
        try { return action.get(); }
        catch(BusinessException e) { if(e.getCause()!=null) throw new BusinessException(e.getErrorCode()); throw e; }
    }
}
