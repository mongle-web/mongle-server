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

    @Version private long version;

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
    }

    public boolean accepts(String attempt) {
        return status == GenerationStatus.PROCESSING && attemptId.equals(attempt);
    }

    public boolean active(Instant now) {
        return status == GenerationStatus.PROCESSING && leaseUntil.isAfter(now);
    }

    public void finish(long revision) {
        status = GenerationStatus.COMPLETED;
        observedRevision = revision;
        leaseUntil = null;
        failureCode = null;
    }

    public void fail(String code) {
        status = GenerationStatus.FAILED;
        failureCode = code;
        leaseUntil = null;
    }
}
