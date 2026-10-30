package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.error.CommonErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class ImageDownloadService {
    private final ImageTransactions transactions;
    private final ImageAssetStore storage;
    private final Clock clock;
    private final Semaphore slots;

    public ImageDownloadService(
            ImageTransactions transactions,
            ImageAssetStore storage,
            Clock authClock,
            ImageExecutionProperties execution) {
        this.transactions = transactions;
        this.storage = storage;
        this.clock = authClock;
        this.slots = new Semaphore(execution.maxConcurrentCalls());
    }

    /** 큐·추가 스레드 없이 한 배치 안에서는 순서대로 서명한다. DB 스냅샷 조회는 서명 전에 끝난다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImageDownloadResponse downloadUrls(Long userId, List<Long> imageIds) {
        if (userId == null
                || userId < 1
                || imageIds == null
                || imageIds.isEmpty()
                || imageIds.size() > 31
                || imageIds.stream().anyMatch(id -> id == null || id < 1)) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        var ids = new LinkedHashSet<>(imageIds);
        if (!slots.tryAcquire()) throw new BusinessException(ImageErrorCode.BUSY);
        try {
            var assets = new HashMap<Long, ImageTransactions.Asset>();
            for (var asset : transactions.assets(userId, ids)) assets.put(asset.imageId(), asset);
            var results = new ArrayList<ImageDownloadResponse.Item>(ids.size());
            for (Long id : ids) {
                var asset = assets.get(id);
                if (asset == null) {
                    results.add(
                            ImageDownloadResponse.Item.failure(id, null, ImageErrorCode.NOT_FOUND));
                    continue;
                }
                try {
                    if (!storage.available()) {
                        results.add(
                                ImageDownloadResponse.Item.failure(
                                        id, asset.imageVersion(), ImageErrorCode.UNAVAILABLE));
                        continue;
                    }
                    var signed =
                            ImageDownloadUrls.requireValid(
                                    storage.temporaryUrl(
                                            asset.storageKey(), ImageDownloadUrls.LIFETIME),
                                    clock.instant());
                    results.add(
                            ImageDownloadResponse.Item.success(id, asset.imageVersion(), signed));
                } catch (RuntimeException failure) {
                    // SDK 메시지·키·서명 URL은 로그나 응답으로 전달하지 않는다.
                    results.add(
                            ImageDownloadResponse.Item.failure(
                                    id, asset.imageVersion(), ImageErrorCode.CALL_FAILED));
                }
            }
            return new ImageDownloadResponse(results);
        } finally {
            slots.release();
        }
    }
}
