package com.mongle.backend.domain.world.composition;
import com.mongle.backend.global.common.GenerationStatus;
public record WorldCompositionResponse(Long worldId,long folderRevision,long compositionRevision,boolean hasResult,
        GenerationStatus status,String failureCode,String attemptId,long jobVersion,boolean selectionChanged) {}
