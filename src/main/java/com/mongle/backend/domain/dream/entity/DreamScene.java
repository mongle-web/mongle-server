package com.mongle.backend.domain.dream.entity;

import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.util.Objects;

@Getter
@Entity
@Table(name = "dream_scenes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DreamScene extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    // 원문 삭제 후에도 분석 결과는 남기므로 꿈 연결은 해제할 수 있다.
    @JoinColumn(name = "dream_id")
    private Dream dream;

    // 꿈이 삭제된 뒤에도 분석 결과의 소유자를 확인할 수 있어야 한다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_disconnected_from_previous", nullable = false)
    @ColumnDefault("false")
    private boolean disconnectedFromPrevious;

    @Builder(access = AccessLevel.PRIVATE)
    private DreamScene(
            Dream dream, int sequenceNo, String content, boolean disconnectedFromPrevious) {
        this.dream = Objects.requireNonNull(dream, "꿈 기록이 필요합니다.");
        this.user = dream.getUser();
        this.sequenceNo = sequenceNo;
        this.content = Objects.requireNonNull(content, "장면 내용이 필요합니다.");
        this.disconnectedFromPrevious = disconnectedFromPrevious;
    }

    public static DreamScene create(
            Dream dream, int sequenceNo, String content, boolean disconnectedFromPrevious) {
        return builder()
                .dream(dream)
                .sequenceNo(sequenceNo)
                .content(content)
                .disconnectedFromPrevious(disconnectedFromPrevious)
                .build();
    }
}
