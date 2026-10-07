package com.mongle.backend.domain.dream.entity;

import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.*;

@Getter
@Entity
@Table(
        name = "dreams",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_dreams_user_date",
                        columnNames = {"user_id", "dreamed_at"}))
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

    @Column(name = "dreamed_at", nullable = false, updatable = false)
    private LocalDate dreamedAt;

    @Column(length = 100)
    private String title;

    @ElementCollection
    @CollectionTable(
            name = "dream_emotions",
            joinColumns = @JoinColumn(name = "dream_id"),
            uniqueConstraints =
                    @UniqueConstraint(
                            name = "uk_dream_emotions",
                            columnNames = {"dream_id", "emotion"}))
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "emotion", nullable = false, length = 20)
    private Set<DreamEmotion> emotions = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "record_status", nullable = false, length = 30)
    @ColumnDefault("'EMOTION_PENDING'")
    private DreamRecordStatus recordStatus;

    @Column(name = "is_edited", nullable = false)
    @ColumnDefault("false")
    private boolean edited;

    @Version
    @Column(nullable = false)
    @ColumnDefault("0")
    private long revision;

    // 수정 요청 충돌을 막는 revision과 AI 입력의 출처 버전을 분리한다.
    @Column(name = "source_revision", nullable = false)
    @ColumnDefault("0")
    private long sourceRevision;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @ColumnDefault("'PENDING'")
    @Column(name = "analysis_status", nullable = false, length = 30)
    @OptimisticLock(excluded = true)
    private GenerationStatus analysisStatus = GenerationStatus.PENDING;

    private Dream(User user, String text, LocalDate date, DreamRecordStatus status) {
        this.user = Objects.requireNonNull(user, "사용자가 필요합니다.");
        this.dreamedAt = Objects.requireNonNull(date, "꿈 날짜가 필요합니다.");
        DreamPolicy.text(text, status == DreamRecordStatus.DRAFT);
        this.originalText = text;
        this.recordStatus = status;
    }

    public static Dream create(User user, String text, LocalDate date) {
        return new Dream(user, text, date, DreamRecordStatus.EMOTION_PENDING);
    }

    public static Dream draft(User user, String text, LocalDate date) {
        return new Dream(user, text, date, DreamRecordStatus.DRAFT);
    }

    public void checkRevision(Long expected) {
        if (expected == null || expected != revision) {
            throw new BusinessException(DreamErrorCode.VERSION_CONFLICT);
        }
    }

    public void saveDraft(String text) {
        if (recordStatus == DreamRecordStatus.COMPLETED) {
            throw new BusinessException(DreamErrorCode.INVALID_STATE);
        }
        DreamPolicy.text(text, recordStatus == DreamRecordStatus.DRAFT);
        if (!Objects.equals(originalText, text)) {
            originalText = text;
            sourceRevision++;
        }
    }

    public void submit() {
        // 같은 요청을 재시도해도 완료 단계를 되돌리지 않는다.
        if (recordStatus == DreamRecordStatus.EMOTION_PENDING) {
            return;
        }
        requireState(DreamRecordStatus.DRAFT);
        DreamPolicy.text(originalText, false);
        recordStatus = DreamRecordStatus.EMOTION_PENDING;
    }

    public void complete(List<DreamEmotion> values) {
        requireState(DreamRecordStatus.EMOTION_PENDING);
        DreamPolicy.emotions(values);
        emotions.clear();
        emotions.addAll(values);
        sourceRevision++;
        recordStatus = DreamRecordStatus.COMPLETED;
    }

    public void update(
            boolean textProvided,
            String text,
            boolean emotionsProvided,
            List<DreamEmotion> values,
            boolean titleProvided,
            String nextTitle) {
        requireState(DreamRecordStatus.COMPLETED);

        if (textProvided) {
            DreamPolicy.text(text, false);
        }

        if (emotionsProvided) {
            DreamPolicy.emotions(values);

            if (!emotions.equals(new HashSet<>(values))) {
                throw new BusinessException(DreamErrorCode.EMOTIONS_IMMUTABLE);
            }
        }

        String normalizedTitle = title;

        if (titleProvided && !Objects.equals(title, nextTitle)) {
            // 기존 20자 초과 제목을 그대로 재전달한 경우에는 데이터 변경 없이 유지한다.
            normalizedTitle = DreamPolicy.editableTitle(nextTitle);
        }

        boolean textChanged = textProvided && !Objects.equals(originalText, text);
        boolean titleChanged = titleProvided && !Objects.equals(title, normalizedTitle);

        // 모든 입력을 검증한 뒤 변경한다. 감정은 완성 시 선택한 값을 유지한다.
        if (textChanged) {
            originalText = text;
            sourceRevision++;
        }

        if (titleChanged) {
            title = normalizedTitle;
        }

        if (textChanged || titleChanged || (titleProvided && normalizedTitle == null)) {
            edited = true;
        }
    }

    public void applyGeneratedTitle(String generatedTitle, long sourceRevision) {
        DreamPolicy.title(generatedTitle);
        // 생성 중 사용자가 수정했거나 제목을 직접 정한 경우에는 덮어쓰지 않는다.
        if (this.sourceRevision == sourceRevision && title == null && !edited) {
            title = generatedTitle;
        }
    }

    public void changeAnalysisStatus(GenerationStatus status) {
        analysisStatus = Objects.requireNonNull(status);
    }

    private void requireState(DreamRecordStatus expected) {
        if (recordStatus != expected) {
            throw new BusinessException(DreamErrorCode.INVALID_STATE);
        }
    }
}
