package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.util.*;

/** 비공개 정리 대상. 원본으로 향하는 FK나 원문/결과/서명 URL은 저장하지 않는다. */
@Getter
@Entity
@Table(name = "image_cleanup_requests",
        uniqueConstraints = @UniqueConstraint(name = "uk_image_cleanup_key", columnNames = "storage_key"),
        indexes = {
            @Index(name = "idx_cleanup_due", columnList = "status,next_run_at,id"),
            @Index(name = "idx_cleanup_lease", columnList = "status,lease_until,id")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageCleanupRequest extends BaseCreatedEntity {
    public enum Reason { DREAM_DELETED, DISCARDED_UPLOAD }
    public enum Status { PENDING, PROCESSING, RETRY_WAIT, COMPLETED, HELD }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "dream_id", updatable = false)
    private Long dreamId;
    @Column(name = "image_id", nullable = false, updatable = false)
    private Long imageId;
    @Column(name = "storage_key", nullable = false, updatable = false, length = 255)
    private String storageKey;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 30)
    private Reason reason;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private Status status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "claim_token", length = 36)
    private String claimToken;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "lease_until")
    private Instant leaseUntil;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "next_run_at")
    private Instant nextRunAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "completed_at")
    private Instant completedAt;
    // 삭제 시작 표시는 재등록해도 해제하지 않는다. 키는 생성 시도별로 재사용하지 않는다.
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "deletion_started_at")
    private Instant deletionStartedAt;
    @Column(name = "failure_code", length = 50)
    private String failureCode;
    @Column(name = "requeue_requested", nullable = false)
    private boolean requeueRequested;
    @Version private long version;

    public static ImageCleanupRequest create(Long userId, Long dreamId, Long imageId,
            String storageKey, Reason reason, Instant now) {
        if (userId == null || imageId == null || storageKey == null || storageKey.isBlank()
                || storageKey.length() > 255 || reason == null || now == null
                || (reason == Reason.DREAM_DELETED && dreamId == null))
            throw new IllegalArgumentException("파일 정리 대상 식별자가 필요합니다.");
        var request = new ImageCleanupRequest();
        request.userId = userId;
        request.dreamId = dreamId;
        request.imageId = imageId;
        request.storageKey = storageKey;
        request.reason = reason;
        request.pending(now);
        return request;
    }

    public boolean due(Instant now) {
        return ((status == Status.PENDING || status == Status.RETRY_WAIT)
                        && (nextRunAt == null || !nextRunAt.isAfter(now)))
                || (status == Status.PROCESSING && leaseUntil != null && !leaseUntil.isAfter(now));
    }

    public void claim(Instant now, Duration lease) {
        status = Status.PROCESSING;
        attemptCount++;
        claimToken = UUID.randomUUID().toString();
        leaseUntil = now.plus(lease);
        nextRunAt = null;
        failureCode = null;
        requeueRequested = false;
        if (deletionStartedAt == null) deletionStartedAt = now;
    }

    public boolean accepts(String token, Instant now) {
        return status == Status.PROCESSING && Objects.equals(claimToken, token)
                && leaseUntil != null && leaseUntil.isAfter(now);
    }

    public void succeed(Instant now) {
        if (requeueRequested) {
            pending(now); // 삭제 IO 이후 도착했을 수 있는 새 업로드도 다시 확인한다.
            return;
        }
        status = Status.COMPLETED;
        completedAt = now;
        nextRunAt = null;
        failureCode = null;
        clearClaim();
    }

    public void retry(Instant next, String code) {
        status = Status.RETRY_WAIT;
        nextRunAt = next;
        failureCode = code;
        clearClaim();
        requeueRequested = false;
    }

    public void hold(String code) {
        status = Status.HELD;
        nextRunAt = null;
        failureCode = code;
        clearClaim();
        requeueRequested = false;
    }

    public void postponeWithoutAttempt(Instant next) {
        attemptCount = Math.max(0, attemptCount - 1);
        retry(next, "STORAGE_UNAVAILABLE");
    }

    public void requestedAgain(Instant now) {
        if (status == Status.COMPLETED || status == Status.HELD) pending(now);
        else if (status == Status.PROCESSING) requeueRequested = true;
        // 대기/실패 요청의 재전송으로 기존 백오프나 재시도 상한을 초기화하지 않는다.
    }

    private void pending(Instant now) {
        status = Status.PENDING;
        attemptCount = 0;
        nextRunAt = now;
        completedAt = null;
        failureCode = null;
        requeueRequested = false;
        clearClaim();
    }

    private void clearClaim() {
        claimToken = null;
        leaseUntil = null;
    }
}
