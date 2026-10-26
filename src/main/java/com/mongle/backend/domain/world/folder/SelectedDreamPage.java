package com.mongle.backend.domain.world.folder;

import org.jspecify.annotations.Nullable;
import java.util.List;

public record SelectedDreamPage(Long worldId, long revision, long selectedDreamCount,
        List<SelectedDreamResponse> items, boolean hasNext, @Nullable String nextCursor) {
    public SelectedDreamPage { items = List.copyOf(items); }
}
