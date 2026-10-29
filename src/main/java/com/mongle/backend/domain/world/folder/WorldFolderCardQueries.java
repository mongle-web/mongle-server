package com.mongle.backend.domain.world.folder;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
@RequiredArgsConstructor
public class WorldFolderCardQueries {
    private final JdbcTemplate jdbc;

    Map<Long, WorldFolderCardResponse.RepresentativeDream> representatives(Long user, List<Long> worlds) {
        if (worlds.isEmpty()) return Map.of();
        var result = new HashMap<Long, WorldFolderCardResponse.RepresentativeDream>();
        new NamedParameterJdbcTemplate(jdbc).query(
                "select e.world_id,d.id,d.title,d.dreamed_at from world_composition_entries e"
                        + " join dreams d on d.id=e.dream_id and d.user_id=e.user_id"
                        + " where e.user_id=:user and e.world_id in (:worlds) and e.candidate=false"
                        + " and not exists(select 1 from world_composition_entries earlier join dreams previous"
                        + " on previous.id=earlier.dream_id and previous.user_id=earlier.user_id"
                        + " where earlier.world_id=e.world_id and earlier.user_id=e.user_id and earlier.candidate=false"
                        + " and (previous.dreamed_at<d.dreamed_at or (previous.dreamed_at=d.dreamed_at and previous.id<d.id)))",
                Map.of("user", user, "worlds", worlds), r -> {
                    result.put(r.getLong("world_id"), new WorldFolderCardResponse.RepresentativeDream(
                            r.getLong("id"), r.getString("title"), r.getDate("dreamed_at").toLocalDate()));
                });
        return result;
    }
}
