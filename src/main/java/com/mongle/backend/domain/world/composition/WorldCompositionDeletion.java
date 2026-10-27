package com.mongle.backend.domain.world.composition;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Caller holds the same user lock as dream mutations and world generation. */
@Service
@RequiredArgsConstructor
public class WorldCompositionDeletion {
    private final WorldCompositionRepository worlds;
    private final WorldCompositionEntryRepository entries;
    private final com.mongle.backend.domain.world.image.WorldImageDeletion images;

    @Transactional(propagation = Propagation.MANDATORY)
    public void dreamDeleted(Long user, Long dream) {
        images.dreamDeleted(user, dream);
        for (var world : worlds.usingDream(user, dream)) {
            boolean removed = false, previousRemoved = false;
            var published =
                    entries.findByWorldIdAndCandidateOrderByPositionAsc(world.getWorldId(), false);
            for (var entry : published) {
                if (entry.getDreamId().equals(dream)) {
                    entries.delete(entry);
                    removed = true;
                    previousRemoved = true;
                } else if (previousRemoved) {
                    entry.gap();
                    previousRemoved = false;
                }
            }
            entries.deleteLane(world.getWorldId(), true);
            world.sourceDeleted(removed, dream);
        }
        entries.flush();
        worlds.flush();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void folderDeleted(Long user, Long world) {
        images.folderDeleted(user, world);
        var found = worlds.findByWorldIdAndUserId(world, user);
        if (found.isEmpty()) return;
        entries.deleteWorld(world);
        entries.flush();
        worlds.delete(found.get());
        worlds.flush();
    }
}
