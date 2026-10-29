package com.mongle.backend.domain.world.folder;

import org.jspecify.annotations.Nullable;
import java.util.List;

public record WorldFolderPage(List<WorldFolderCardResponse> items, boolean hasNext, @Nullable Long nextBeforeId) {
    public WorldFolderPage { items = List.copyOf(items); }
}
