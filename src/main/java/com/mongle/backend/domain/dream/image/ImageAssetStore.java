package com.mongle.backend.domain.dream.image;

import java.time.Duration;
import java.time.Instant;

/** 비공개 저장소 포트. key는 도메인이 만든 전용 시도 경로이며 공개 URL을 DB에 저장하지 않는다. */
public interface ImageAssetStore {
    default boolean available() {
        return true;
    }

    void put(String key, ImagePayload payload);

    void delete(String key);

    DownloadUrl temporaryUrl(String key, Duration lifetime);

    record DownloadUrl(String url, Instant expiresAt) {
        @Override
        public String toString() {
            return "ImageDownloadUrl[expiresAt=" + expiresAt + "]";
        }
    }
}
