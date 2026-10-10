package com.mongle.backend.domain.dream.story;

import com.mongle.backend.global.common.BaseCreatedEntity;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.Immutable;

@Getter
@Entity
@Immutable
@Table(
        name = "dream_story_versions",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_story_version_generation",
                        columnNames = {"story_id", "generation_key"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryResultVersion extends BaseCreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false, updatable = false)
    private DreamStory story;

    @Column(name = "generation_key", nullable = false, updatable = false, length = 36)
    private String generationKey;

    @Column(name = "source_revision", nullable = false, updatable = false)
    private long sourceRevision;

    @Column(name = "prompt_version", nullable = false, updatable = false, length = 50)
    private String promptVersion;

    @Column(name = "result_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String resultJson;

    public static StoryResultVersion capture(DreamStory story, String encoded) {
        var result = new StoryResultVersion();
        result.story = story;
        result.generationKey = story.getAttemptId();
        result.sourceRevision = story.getSourceRevision();
        result.promptVersion = story.getPromptVersion();
        result.resultJson = encoded;
        return result;
    }
}
