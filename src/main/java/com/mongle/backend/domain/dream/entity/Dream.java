package com.mongle.backend.domain.dream.entity;

import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.global.common.GenerationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.Objects;

@Getter
@Entity
@Table(name = "dreams")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dream extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_text", nullable = false, columnDefinition = "TEXT")
    private String originalText;

    @Column(name = "dreamed_at")
    private LocalDate dreamedAt;

    @Column(name = "representative_emotion", length = 50)
    private String representativeEmotion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @ColumnDefault("'PENDING'")
    @Column(name = "analysis_status", nullable = false, length = 30)
    private GenerationStatus analysisStatus = GenerationStatus.PENDING;

    @Builder(access = AccessLevel.PRIVATE)
    private Dream(User user, String originalText, LocalDate dreamedAt, String representativeEmotion) {
        this.user = Objects.requireNonNull(user, "사용자는 필수입니다.");
        this.originalText = Objects.requireNonNull(originalText, "꿈 원문은 필수입니다.");
        this.dreamedAt = dreamedAt;
        this.representativeEmotion = representativeEmotion;
    }

    public static Dream create(User user, String originalText, LocalDate dreamedAt, String representativeEmotion) {
        return builder()
                .user(user)
                .originalText(originalText)
                .dreamedAt(dreamedAt)
                .representativeEmotion(representativeEmotion)
                .build();
    }
}
