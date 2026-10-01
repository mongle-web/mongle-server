package com.mongle.backend.domain.dream.dto;

import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.global.common.GenerationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DreamResponse(Long dreamId, LocalDate dreamedAt, String originalText, String title,
                            List<DreamEmotion> emotions, DreamRecordStatus recordStatus,
                            GenerationStatus analysisStatus, boolean edited, long revision,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static DreamResponse from(Dream dream) {
        return new DreamResponse(dream.getId(), dream.getDreamedAt(), dream.getOriginalText(), dream.getTitle(),
                dream.getEmotions().stream().sorted().toList(), dream.getRecordStatus(), dream.getAnalysisStatus(),
                dream.isEdited(), dream.getRevision(), dream.getCreatedAt(), dream.getUpdatedAt());
    }
}
