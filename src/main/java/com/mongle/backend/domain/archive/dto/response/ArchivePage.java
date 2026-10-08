package com.mongle.backend.domain.archive.dto.response;

import org.jspecify.annotations.Nullable;
import java.util.List;

/** 전체 count 쿼리 없이 size+1개로 다음 페이지를 판별한다. 마지막 페이지의 nextCursor는 null이다. */
public record ArchivePage(List<ArchiveItem> items, boolean hasNext, @Nullable String nextCursor) {
    public ArchivePage {
        items = List.copyOf(items);
    }
}
