package com.mongle.backend.domain.world.composition;

import java.util.List;

public record WorldImpactPage(List<Impact> items, boolean hasNext, Long nextBeforeId) {
    public record Impact(
            Long worldId,
            String name,
            boolean selected,
            boolean usedInComposition,
            long compositionRevision) {}
}
