package com.mongle.backend.domain.dream.image.s3;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("mongle.image.s3")
public record ImageS3Properties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String bucket,
        @DefaultValue("") String region,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("4s") Duration attemptTimeout,
        @DefaultValue("10s") Duration totalTimeout,
        @DefaultValue("2") int maxAttempts) {
    public ImageS3Properties {
        bucket = bucket == null ? "" : bucket.strip();
        region = region == null ? "" : region.strip();
        if (enabled && (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..") || bucket.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
                || !region.matches("[a-z]{2}(?:-[a-z0-9]+)+-[0-9]+"))) {
            throw new IllegalArgumentException("S3 이미지 저장소의 버킷과 리전 설정이 필요합니다.");
        }
        requireDuration(connectTimeout);
        requireDuration(attemptTimeout);
        requireDuration(totalTimeout);
        if (connectTimeout.compareTo(attemptTimeout) > 0
                || attemptTimeout.compareTo(totalTimeout) >= 0 || maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("S3 이미지 저장소의 시간 제한 또는 시도 상한이 잘못되었습니다.");
        }
    }

    private static void requireDuration(Duration value) {
        if (value == null || value.compareTo(Duration.ofMillis(100)) < 0
                || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("S3 이미지 저장소 시간 제한은 100ms~30s여야 합니다.");
        }
    }

    @Override public String toString() {
        return "ImageS3Properties[enabled=" + enabled + "]";
    }
}
