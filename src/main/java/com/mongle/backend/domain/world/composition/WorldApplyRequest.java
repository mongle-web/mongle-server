package com.mongle.backend.domain.world.composition;
import jakarta.validation.constraints.*;
public record WorldApplyRequest(@NotNull @PositiveOrZero Long folderRevision,@NotNull @PositiveOrZero Long compositionRevision,
        @NotNull @Positive Long storyVersionId) {}
