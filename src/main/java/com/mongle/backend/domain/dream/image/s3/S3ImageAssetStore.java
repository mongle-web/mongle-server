package com.mongle.backend.domain.dream.image.s3;

import com.mongle.backend.domain.dream.image.*;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** 동기 IO는 기존 이미지 완료 풀/정리 실행기에서만 실행한다. 소유권은 도메인이 검사한다. */
public final class S3ImageAssetStore implements ImageAssetStore, AutoCloseable {
    private static final Pattern KEY = Pattern.compile("dream-images/[1-9][0-9]{0,18}/[1-9][0-9]{0,18}/"
            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg)");
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Clock clock;
    private final DefaultCredentialsProvider ownedCredentials;
    private final AtomicBoolean closed = new AtomicBoolean();

    public S3ImageAssetStore(S3Client client, S3Presigner presigner, String bucket, Clock clock) {
        this(client, presigner, bucket, clock, null);
    }

    private S3ImageAssetStore(S3Client client, S3Presigner presigner, String bucket, Clock clock,
            DefaultCredentialsProvider credentials) {
        this.client = Objects.requireNonNull(client);
        this.presigner = Objects.requireNonNull(presigner);
        this.bucket = Objects.requireNonNull(bucket);
        this.clock = Objects.requireNonNull(clock);
        this.ownedCredentials = credentials;
    }

    /** 생성 시 자격 증명을 해석하거나 AWS 상태를 조회하지 않는다. */
    public static S3ImageAssetStore open(ImageS3Properties properties, ImageCleanupProperties cleanup,
            ImageExecutionProperties execution, Clock clock) {
        if (!properties.enabled() || properties.totalTimeout().compareTo(cleanup.lease()) >= 0) {
            throw new IllegalArgumentException("S3 저장소는 활성화해야 하며 호출 제한은 정리 lease보다 짧아야 합니다.");
        }
        var credentials = DefaultCredentialsProvider.builder().build();
        S3Client client = null;
        S3Presigner presigner = null;
        try {
            var region = Region.of(properties.region());
            client = S3Client.builder().region(region).credentialsProvider(credentials)
                    .httpClientBuilder(Apache5HttpClient.builder()
                            .maxConnections(Math.min(execution.maxConcurrentCalls(), execution.resultThreads())
                                    + cleanup.maxConcurrentCalls())
                            .connectionTimeout(properties.connectTimeout())
                            .connectionAcquisitionTimeout(properties.connectTimeout())
                            .socketTimeout(properties.attemptTimeout()))
                    .overrideConfiguration(b -> b.apiCallTimeout(properties.totalTimeout())
                            .apiCallAttemptTimeout(properties.attemptTimeout())
                            .retryStrategy(StandardRetryStrategy.builder().maxAttempts(properties.maxAttempts()).build()))
                    .build();
            presigner = S3Presigner.builder().region(region).credentialsProvider(credentials).build();
            return new S3ImageAssetStore(client, presigner, properties.bucket(), clock, credentials);
        } catch (RuntimeException ex) {
            closeResource(client);
            closeResource(presigner);
            closeResource(credentials);
            throw failure();
        }
    }

    /** 설정되어 있음을 나타내며 자격 증명·권한·버킷 접근 성공을 보장하지 않는다. */
    @Override public boolean available() { return !closed.get(); }

    @Override public void put(String key, ImagePayload payload) {
        requireKey(key);
        if (payload == null) throw new IllegalArgumentException("검증된 이미지가 필요합니다.");
        var bytes = payload.bytes();
        if (bytes.length == 0 || bytes.length > ImagePayload.MAX_BYTES
                || !(key.endsWith(".png") && "image/png".equals(payload.contentType())
                    || key.endsWith(".jpg") && "image/jpeg".equals(payload.contentType()))) {
            throw new IllegalArgumentException("검증된 이미지 형식과 저장 경로가 일치해야 합니다.");
        }
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType(payload.contentType()).contentLength((long) bytes.length).build(),
                    RequestBody.fromBytes(bytes));
        } catch (RuntimeException ex) { throw failure(); }
    }

    @Override public void delete(String key) {
        requireKey(key);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception ex) {
            // 잘못된 버킷/권한의 404/403은 성공으로 숨기지 않는다.
            if (ex.statusCode() == 404 && ex.awsErrorDetails() != null
                    && "NoSuchKey".equals(ex.awsErrorDetails().errorCode())) return;
            throw failure();
        } catch (RuntimeException ex) { throw failure(); }
    }

    @Override public DownloadUrl temporaryUrl(String key, Duration lifetime) {
        requireKey(key);
        if (lifetime == null || lifetime.compareTo(Duration.ofSeconds(1)) < 0
                || lifetime.compareTo(Duration.ofMinutes(5)) > 0 || lifetime.getNano() != 0) {
            throw new IllegalArgumentException("이미지 URL 유효기간은 1~300초여야 합니다.");
        }
        try {
            var before = clock.instant();
            var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(lifetime)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build()).build());
            var uri = signed.url().toURI();
            if (!signed.isBrowserExecutable() || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null || signed.expiration() == null
                    || !signed.expiration().isAfter(clock.instant())
                    || signed.expiration().isAfter(clock.instant().plus(lifetime))) throw failure();
            var expiresAt = signed.expiration().isBefore(before.plus(lifetime))
                    ? signed.expiration() : before.plus(lifetime);
            if (!expiresAt.isAfter(clock.instant())) throw failure();
            return new DownloadUrl(uri.toASCIIString(), expiresAt);
        } catch (Exception ex) { throw failure(); }
    }

    private void requireKey(String key) {
        if (closed.get()) throw failure();
        if (key == null || !KEY.matcher(key).matches())
            throw new IllegalArgumentException("이미지 전용 저장 경로가 필요합니다.");
    }

    // SDK 예외에는 요청 경로/외부 오류 본문이 있을 수 있어 원인을 전달하지 않는다.
    private static IllegalStateException failure() {
        return new IllegalStateException("이미지 저장소 요청을 완료하지 못했습니다.");
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        boolean success = closeResource(client);
        success = closeResource(presigner) && success;
        success = closeResource(ownedCredentials) && success;
        if (!success) throw failure();
    }

    private static boolean closeResource(AutoCloseable resource) {
        try { if (resource != null) resource.close(); return true; }
        catch (Exception ex) { return false; }
    }
}
