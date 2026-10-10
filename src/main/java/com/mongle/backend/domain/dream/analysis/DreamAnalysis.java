package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.global.common.GenerationStatus;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Entity
@Table(
        name = "dream_analyses",
        indexes =
                @Index(
                        name = "idx_analysis_user_status_date",
                        columnList = "user_id,status,dreamed_at"),
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_dream_analysis_dream", columnNames = "dream_id"),
            @UniqueConstraint(name = "uk_dream_analysis_source", columnNames = "source_dream_id")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DreamAnalysis extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dream_id")
    private Dream dream;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "source_dream_id", nullable = false, updatable = false)
    private Long sourceDreamId;

    @Column(name = "dreamed_at", nullable = false, updatable = false)
    private LocalDate dreamedAt;

    @Column(name = "source_revision", nullable = false)
    private long sourceRevision;

    @Column(name = "observed_revision", nullable = false)
    private long observedRevision;

    @Column(name = "prompt_version", nullable = false, length = 50)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private GenerationStatus status;

    @Column(name = "attempt_id", length = 36)
    private String attemptId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    // 기존 scene-v1 분석은 null/빈 목록으로 유지한다. 원문 삭제 후에도 분석에 보존한다.
    // H2는 보조 평면 문자를 UTF-16 두 단위로 센다. API 제한은 20 코드포인트다.
    @Column(name = "generated_title", length = 40)
    private String generatedTitle;

    @ElementCollection
    @CollectionTable(
            name = "dream_analysis_display_keywords",
            joinColumns = @JoinColumn(name = "analysis_id"))
    @OrderColumn(name = "keyword_order")
    @Column(name = "keyword", nullable = false, length = 40)
    private List<String> displayKeywords = new ArrayList<>();

    public List<String> getDisplayKeywords() {
        return List.copyOf(displayKeywords);
    }

    @Version private long version;

    @Column(name = "result_revision")
    private Long resultRevision;

    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "source_emotions", length = 100)
    private String sourceEmotions;

    @Column(name = "pending_result_json", columnDefinition = "TEXT")
    private String pendingResultJson;

    @Column(name = "regenerating", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean regenerating;

    /** 새 분석을 임시 저장하고, 분석·서사 전체 성공까지 기존 표시 결과를 유지한다. */
    public void requestRegeneration() {
        regenerating = true;
        pendingResultJson = null;
        status = GenerationStatus.PENDING;
        attemptId = null;
        leaseUntil = null;
        failureCode = null;
    }

    public void stage(String encoded) {
        pendingResultJson = encoded;
        status = GenerationStatus.COMPLETED;
        leaseUntil = null;
        failureCode = null;
    }

    public long visibleRevision() {
        return resultRevision == null ? observedRevision : resultRevision;
    }

    public boolean hasResult() {
        return resultRevision != null;
    }

    public static DreamAnalysis create(Dream dream) {
        var a = new DreamAnalysis();
        a.dream = dream;
        a.user = dream.getUser();
        a.sourceDreamId = dream.getId();
        a.dreamedAt = dream.getDreamedAt();
        return a;
    }

    public void start(long revision, Instant now, Duration lease) {
        sourceRevision = revision;
        observedRevision = revision;
        status = GenerationStatus.PROCESSING;
        attemptId = UUID.randomUUID().toString();
        leaseUntil = now.plus(lease);
        failureCode = null;
        promptVersion = StructurePrompt.VERSION;
        sourceText = dream.getOriginalText();
        sourceEmotions = dream.getEmotions().stream().sorted().map(Enum::name)
                .collect(java.util.stream.Collectors.joining(","));
    }

    public boolean accepts(String attempt) {
        return status == GenerationStatus.PROCESSING && attemptId.equals(attempt);
    }

    public boolean active(Instant now) {
        return status == GenerationStatus.PROCESSING && leaseUntil.isAfter(now);
    }

    public void finish(long revision, StructureResult result) {
        generatedTitle = result.generatedTitle();
        displayKeywords.clear();
        displayKeywords.addAll(result.displayKeywords());
        status = GenerationStatus.COMPLETED;
        observedRevision = revision;
        resultRevision = revision;
        regenerating = false;
        pendingResultJson = null;
        leaseUntil = null;
        failureCode = null;
    }

    public void fail(String code) {
        status = GenerationStatus.FAILED;
        failureCode = code;
        leaseUntil = null;
    }
}
