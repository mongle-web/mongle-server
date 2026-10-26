package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.net.URI;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class DreamImageService {
    private final ImageTransactions transactions;
    private final ImageGenerator generator;
    private final ImageAssetStore storage;
    private final Clock authClock;
    private final ImageGenerationResources resources;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<ImageResponse> generate(Long userId, Long dreamId, ImageRequest request) {
        var permit = new AtomicReference<ImageGenerationResources.Permit>();
        ImageTransactions.Reservation reservation;
        try {
            reservation = transactions.begin(
                    userId, dreamId, request, generator.available() && storage.available(),
                    () -> {
                        try {
                            permit.set(resources.reserve());
                        } catch (RejectedExecutionException ex) {
                            throw new BusinessException(ImageErrorCode.BUSY);
                        }
                    });
        } catch (RuntimeException ex) {
            if (permit.get() != null) permit.get().close();
            throw ex;
        }
        if (reservation.input() == null) {
            return CompletableFuture.completedFuture(reservation.response());
        }
        var input = reservation.input();
        return permit.get().execute(
                () -> generator.generate(input),
                (bytes, failure) -> complete(input, bytes, failure));
    }

    /** 디코딩·스토리지·DB 기록은 이미지 전용 완료 풀에서만 실행한다. */
    private ImageResponse complete(ImageGenerator.Input input, byte[] bytes, Throwable failure) {
        if (failure != null) {
            while (failure instanceof CompletionException && failure.getCause() != null) {
                failure = failure.getCause();
            }
            var original = failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("이미지 제공자 호출 실패", failure);
            String code = original instanceof BusinessException business
                    && business.getErrorCode() == ImageErrorCode.INVALID_OUTPUT
                    ? "INVALID_OUTPUT" : "CALL_FAILED";
            return fail(input, code, original);
        }
        ImagePayload payload;
        try {
            payload = ImagePayload.parse(bytes);
        } catch (RuntimeException ex) {
            return fail(input, "INVALID_OUTPUT", ex);
        }
        String key =
                "dream-images/"
                        + input.userId()
                        + "/"
                        + input.imageId()
                        + "/"
                        + input.attemptId()
                        + (payload.contentType().equals("image/png") ? ".png" : ".jpg");
        try {
            storage.put(key, payload);
        } catch (RuntimeException ex) {
            cleanup(key, ex);
            return fail(input, "STORAGE_FAILED", ex);
        }
        try {
            var completion = transactions.finish(input, key, payload);
            if (!completion.stored()) cleanup(key, null);
            return completion.response();
        } catch (RuntimeException ex) {
            cleanup(key, ex);
            try {
                transactions.fail(input, "PERSISTENCE_FAILED");
            } catch (RuntimeException recovery) {
                ex.addSuppressed(recovery);
            }
            throw new BusinessException(ImageErrorCode.CALL_FAILED, ex);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImageAssetStore.DownloadUrl downloadUrl(Long userId, Long imageId) {
        String key = transactions.assetKey(userId, imageId);
        if (!storage.available()) throw new BusinessException(ImageErrorCode.UNAVAILABLE);
        try {
            var result = storage.temporaryUrl(key, Duration.ofMinutes(5));
            var uri = URI.create(result.url());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || result.expiresAt() == null
                    || !result.expiresAt().isAfter(authClock.instant())
                    || result.expiresAt()
                            .isAfter(authClock.instant().plus(Duration.ofMinutes(5)))) {
                throw new IllegalArgumentException("유효한 5분 이내 HTTPS 다운로드 URL이 필요합니다.");
            }
            return result;
        } catch (RuntimeException ex) {
            throw new BusinessException(ImageErrorCode.CALL_FAILED, ex);
        }
    }

    private ImageResponse fail(ImageGenerator.Input input, String code, RuntimeException ex) {
        try {
            return transactions.fail(input, code);
        } catch (RuntimeException recovery) {
            ex.addSuppressed(recovery);
            throw new BusinessException(ImageErrorCode.CALL_FAILED, ex);
        }
    }

    private void cleanup(String key, RuntimeException original) {
        try {
            storage.delete(key);
        } catch (RuntimeException cleanupFailure) {
            if (original != null) original.addSuppressed(cleanupFailure);
            // 키·서명 URL·외부 예외 메시지를 로그에 노출하지 않는다.
            log.warn("이미지 시도 객체 정리에 실패했습니다. 미참조 객체 회수가 필요합니다.");
        }
    }
}
