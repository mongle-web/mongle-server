package com.mongle.backend.domain.world.summary;

import jakarta.persistence.*;

import lombok.*;

/** 출처 식별자·버전만 저장하며 서사/분석/감정 본문은 복제하지 않는다. */
@Getter
@Entity
@Table(
        name = "world_summary_sources",
        indexes =
                @Index(
                        name = "idx_world_summary_source_dream",
                        columnList = "user_id,dream_id,world_id"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_world_summary_source",
                        columnNames = {"world_id", "candidate", "dream_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldSummarySource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "world_id", nullable = false)
    private Long worldId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "dream_id", nullable = false)
    private Long dreamId;

    @Column(name = "story_version_id", nullable = false)
    private Long storyVersionId;

    @Column(name = "source_revision", nullable = false)
    private long sourceRevision;

    @Column(name = "observed_revision", nullable = false)
    private long observedRevision;

    @Column(nullable = false)
    private long position;

    @Column(name = "bridge_id")
    private Long bridgeId;

    @Column(nullable = false)
    private boolean candidate;

    static WorldSummarySource create(WorldSummary s, WorldSummaryEvidence.Source ref) {
        var r = new WorldSummarySource();
        r.worldId = s.getWorldId();
        r.userId = s.getUserId();
        r.dreamId = ref.dreamId();
        r.storyVersionId = ref.storyVersionId();
        r.sourceRevision = ref.sourceRevision();
        r.observedRevision = ref.observedRevision();
        r.position = ref.position();
        r.bridgeId = ref.bridgeId();
        r.candidate = true;
        return r;
    }
}
