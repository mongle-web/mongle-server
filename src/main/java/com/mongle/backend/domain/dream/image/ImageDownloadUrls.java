package com.mongle.backend.domain.dream.image;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/** 단건·일괄 발급이 같은 HTTPS·만료 정책을 사용한다. */
final class ImageDownloadUrls {
    static final Duration LIFETIME = Duration.ofMinutes(5);

    private ImageDownloadUrls() {}

    static ImageAssetStore.DownloadUrl requireValid(
            ImageAssetStore.DownloadUrl result, Instant now) {
        var uri = URI.create(result.url());
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || result.expiresAt() == null
                || !result.expiresAt().isAfter(now)
                || result.expiresAt().isAfter(now.plus(LIFETIME))) {
            throw new IllegalArgumentException("유효한 5분 이내 HTTPS 다운로드 URL이 필요합니다.");
        }
        return result;
    }
}
