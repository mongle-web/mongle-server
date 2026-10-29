package com.mongle.backend.domain.world.summary;

import com.mongle.backend.global.common.GenerationStatus;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 호출자는 세계관/꿈 변경과 동일한 회원 잠금을 보유한다. 외부 AI를 호출하지 않는다. */
@Service
@RequiredArgsConstructor
public class WorldSummaryLifecycle {
    private final WorldSummaryRepository summaries;
    private final WorldSummarySourceRepository sources;
    private final WorldSummaryQueries queries;
    private final WorldSummaryProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public void published(Long user, Long world, long revision) {
        var found = summaries.findByWorldIdAndUserId(world, user);
        if (!properties.enabled()) {
            found.ifPresent(
                    s -> {
                        if (s.getStatus() == GenerationStatus.PENDING
                                || s.getStatus() == GenerationStatus.PROCESSING)
                            s.fail("INPUT_CHANGED");
                    });
            sources.deleteLane(world, true);
            return;
        }
        var s = found.orElseGet(() -> WorldSummary.create(user, world));
        sources.deleteLane(world, true);
        s.queue(revision);
        summaries.saveAndFlush(s);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void dreamDeleted(Long user, Long dream) {
        var published = new HashSet<>(queries.publishedWorlds(user, dream));
        var affected = new TreeSet<>(sources.worldsForDream(user, dream));
        affected.addAll(published);
        for (var world : affected) {
            var s = summaries.findByWorldIdAndUserId(world, user).orElse(null);
            if (s == null) continue;
            if (sources.existsByWorldIdAndDreamIdAndCandidate(world, dream, false)) {
                s.discardResult();
                sources.deleteLane(world, false);
            }
            if (published.contains(world)
                    || sources.existsByWorldIdAndDreamIdAndCandidate(world, dream, true)) {
                s.fail("SOURCE_DELETED");
                sources.deleteLane(world, true);
            }
        }
        summaries.flush();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void folderDeleted(Long user, Long world) {
        var found = summaries.findByWorldIdAndUserId(world, user);
        if (found.isEmpty()) return;
        sources.deleteLane(world, true);
        sources.deleteLane(world, false);
        summaries.delete(found.get());
        summaries.flush();
    }
}
