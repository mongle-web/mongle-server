package com.mongle.backend.domain.dream.story;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoryResultVersionRepository extends JpaRepository<StoryResultVersion, Long> {
    @Query("select v from StoryResultVersion v join fetch v.story s join fetch s.analysis a"
            + " join fetch a.dream d where v.id=:id and a.user.id=:userId and d.user.id=:userId")
    Optional<StoryResultVersion> findOwnedLive(@Param("id") Long id, @Param("userId") Long userId);

    @Query("select v.id from StoryResultVersion v where v.story.id=:storyId order by v.id desc")
    List<Long> findLatestId(@Param("storyId") Long storyId, Pageable pageable);

    interface Summary {
        Long getId();

        long getSourceRevision();

        String getPromptVersion();

        java.time.LocalDateTime getCreatedAt();

        String getGenerationKey();
    }

    Optional<StoryResultVersion> findByIdAndStoryId(Long id, Long storyId);

    @Query(
            "select v.id as id, v.sourceRevision as sourceRevision, v.promptVersion as"
                + " promptVersion, v.createdAt as createdAt, v.generationKey as generationKey from"
                + " StoryResultVersion v where v.story.id=:storyId and (:before is null or v.id <"
                + " :before) order by v.id desc")
    List<Summary> findPage(
            @Param("storyId") Long storyId, @Param("before") Long before, Pageable pageable);
}
