package com.mongle.backend.domain.world.summary;

import com.mongle.backend.domain.world.composition.WorldCompositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 이미 소유권을 확인한 목록 페이지의 요약을 일괄 조회한다. 생성·잠금·상태 저장은 하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldSummaryReader {
    private final WorldCompositionRepository compositions;
    private final WorldSummaryRepository summaries;
    private final WorldSummaryQueries queries;
    private final WorldSummaryResponses responses;

    public record View(long composedDreamCount, WorldSummaryResponse summary) {}

    public Map<Long, View> readAll(Long user, List<Long> worlds) {
        if (worlds.isEmpty()) return Map.of();
        var compositionById = compositions.findByUserIdAndWorldIdIn(user, worlds).stream()
                .collect(Collectors.toMap(w -> w.getWorldId(), Function.identity()));
        var summaryById = summaries.findByUserIdAndWorldIdIn(user, worlds).stream()
                .collect(Collectors.toMap(WorldSummary::getWorldId, Function.identity()));
        var stats = queries.statsForWorlds(user, worlds);
        var missing = queries.resultSourcesMissingForWorlds(user, worlds);
        var result = new HashMap<Long, View>();
        for (Long world : worlds) {
            var current = stats.get(world);
            result.put(world, new View(current.count(), responses.from(world,
                    compositionById.get(world), summaryById.get(world), current, missing.contains(world))));
        }
        return Map.copyOf(result);
    }
}
