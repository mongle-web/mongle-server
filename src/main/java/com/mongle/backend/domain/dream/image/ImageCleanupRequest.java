package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;

/** 원본 삭제 후에도 남아야 하는 내부 파일 정리 대상. 원문/결과/서명 URL은 저장하지 않는다. */
@Getter
@Entity
@Table(name = "image_cleanup_requests", uniqueConstraints =
        @UniqueConstraint(name = "uk_image_cleanup_key", columnNames = "storage_key"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageCleanupRequest extends BaseCreatedEntity {
    public enum Reason { DREAM_DELETED, DISCARDED_UPLOAD }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 삭제된 부모를 참조하는 FK를 두지 않는다. 늦은 업로드는 dreamId를 알 수 없을 수 있다.
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "dream_id", updatable = false)
    private Long dreamId;
    @Column(name = "image_id", nullable = false, updatable = false)
    private Long imageId;
    @Column(name = "storage_key", nullable = false, updatable = false, length = 255)
    private String storageKey;
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 30)
    private Reason reason;

    public static ImageCleanupRequest create(Long userId, Long dreamId, Long imageId,
            String storageKey, Reason reason) {
        if (userId == null || imageId == null || storageKey == null || storageKey.isBlank()
                || reason == null || (reason == Reason.DREAM_DELETED && dreamId == null))
            throw new IllegalArgumentException("파일 정리 대상 식별자가 필요합니다.");
        var request = new ImageCleanupRequest();
        request.userId = userId;
        request.dreamId = dreamId;
        request.imageId = imageId;
        request.storageKey = storageKey;
        request.reason = reason;
        return request;
    }
}
