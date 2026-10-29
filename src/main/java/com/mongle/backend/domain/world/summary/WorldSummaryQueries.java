package com.mongle.backend.domain.world.summary;

import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
@RequiredArgsConstructor
public class WorldSummaryQueries {
    private final JdbcTemplate jdbc;
    private final WorldSummaryEvidence evidence;
    // 모든 출처는 폴더 소유자 및 원본 꿈까지 역으로 확인한다.
    private static final String FROM_JOINS =
            " from world_composition_entries e join dreams d on d.id=e.dream_id and"
                + " d.user_id=e.user_id join dream_story_versions v on v.id=e.story_version_id join"
                + " dream_stories s on s.id=v.story_id and s.user_id=e.user_id join dream_analyses"
                + " a on a.id=s.analysis_id and a.user_id=e.user_id and"
                + " a.source_dream_id=e.dream_id left join world_bridge_results b on"
                + " b.id=e.bridge_id and b.user_id=e.user_id and b.status='COMPLETED'";
    private static final String JOINS = FROM_JOINS
            + " where e.world_id=? and e.user_id=? and e.candidate=false";

    record Stats(long count, long validCount, long characters, boolean sourceChanged) {}

    Stats stats(Long user, Long world) {
        long count =
                jdbc.queryForObject(
                        "select count(*) from world_composition_entries where world_id=? and"
                            + " user_id=? and candidate=false",
                        Long.class,
                        world,
                        user);
        return jdbc.queryForObject(
                "select"
                    + " count(*),coalesce(sum(char_length(v.result_json)+coalesce(char_length(v.analysis_json),0)"
                    + "+coalesce(char_length(v.source_emotions),0)+coalesce(char_length(b.content),0)+256),0),coalesce(sum(case"
                    + " when d.source_revision<>v.source_revision then 1 else 0 end),0)"
                        + JOINS
                        + " and (e.bridge_id is null or b.id is not null)",
                (r, n) -> new Stats(count, r.getLong(1), r.getLong(2), r.getLong(3) > 0),
                world,
                user);
    }

    /** 본문을 로딩하지 않고 페이지 안의 공개 구성 개수·출처 변경을 일괄 조회한다. */
    Map<Long, Stats> statsForWorlds(Long user, List<Long> worlds) {
        if (worlds.isEmpty()) return Map.of();
        var named = new NamedParameterJdbcTemplate(jdbc);
        var parameters = Map.of("user", user, "worlds", worlds);
        var totals = new HashMap<Long, Long>();
        named.query("select world_id,count(distinct dream_id) as total from world_composition_entries"
                        + " where user_id=:user and world_id in (:worlds) and candidate=false group by world_id",
                parameters, r -> { totals.put(r.getLong("world_id"), r.getLong("total")); });
        var result = new HashMap<Long, Stats>();
        for (Long world : worlds) result.put(world, new Stats(totals.getOrDefault(world, 0L), 0, 0, false));
        named.query("select e.world_id,count(distinct e.dream_id) as valid_count,"
                        + "coalesce(sum(case when d.source_revision<>v.source_revision then 1 else 0 end),0) as changed_count"
                        + FROM_JOINS + " where e.user_id=:user and e.world_id in (:worlds) and e.candidate=false"
                        + " and (e.bridge_id is null or b.id is not null) group by e.world_id",
                parameters, r -> {
                    Long world = r.getLong("world_id");
                    // 문자 수는 생성 입력 검사에만 필요하므로 카드 조회에서는 계산하지 않는다.
                    result.put(world, new Stats(totals.getOrDefault(world, 0L), r.getLong("valid_count"),
                            0, r.getLong("changed_count") > 0));
                });
        return result;
    }

    Set<Long> resultSourcesMissingForWorlds(Long user, List<Long> worlds) {
        if (worlds.isEmpty()) return Set.of();
        return new HashSet<>(new NamedParameterJdbcTemplate(jdbc).query(
                "select distinct x.world_id from world_summary_sources x left join dreams d on"
                        + " d.id=x.dream_id and d.user_id=x.user_id left join dream_story_versions v on v.id=x.story_version_id"
                        + " where x.user_id=:user and x.world_id in (:worlds) and x.candidate=false"
                        + " and (d.id is null or v.id is null)",
                Map.of("user", user, "worlds", worlds), (r, n) -> r.getLong("world_id")));
    }

    WorldSummaryEvidence.Snapshot snapshot(Long user, Long world, String settings, int max) {
        var stats = stats(user, world);
        if (stats.count() != stats.validCount())
            throw new BusinessException(WorldSummaryErrorCode.INPUT_UNAVAILABLE);
        if (stats.characters() > max) throw new BusinessException(WorldSummaryErrorCode.TOO_LARGE);
        var rows =
                jdbc.query(
                        "select e.dream_id,e.story_version_id,v.source_revision,d.source_revision"
                            + " as observed_revision,"
                            + "e.position,e.bridge_id,v.result_json,v.analysis_json,v.source_emotions,b.content"
                                + JOINS
                                + " order by e.position",
                        (r, n) ->
                                new WorldSummaryEvidence.Row(
                                        new WorldSummaryEvidence.Source(
                                                r.getLong("dream_id"),
                                                r.getLong("story_version_id"),
                                                r.getLong("source_revision"),
                                                r.getLong("observed_revision"),
                                                r.getLong("position"),
                                                r.getObject("bridge_id", Long.class)),
                                        r.getString("result_json"),
                                        r.getString("analysis_json"),
                                        r.getString("source_emotions"),
                                        r.getString("content")),
                        world,
                        user);
        return evidence.assemble(rows, settings, max);
    }

    boolean candidateValid(Long user, Long world) {
        long expected =
                jdbc.queryForObject(
                        "select count(*) from world_summary_sources where world_id=? and user_id=?"
                            + " and candidate=true",
                        Long.class,
                        world,
                        user);
        var stats = stats(user, world);
        if (expected == 0 || expected != stats.count() || stats.count() != stats.validCount())
            return false;
        long matched =
                jdbc.queryForObject(
                        "select count(*)"
                                + JOINS
                                + " and exists(select 1 from world_summary_sources x where"
                                + " x.world_id=e.world_id and x.user_id=e.user_id and"
                                + " x.candidate=true and x.dream_id=e.dream_id and"
                                + " x.story_version_id=e.story_version_id and x.position=e.position"
                                + " and x.source_revision=v.source_revision and"
                                + " x.observed_revision=d.source_revision and"
                                + " (x.bridge_id=e.bridge_id or (x.bridge_id is null and"
                                + " e.bridge_id is null)))",
                        Long.class,
                        world,
                        user);
        return expected == matched;
    }

    boolean resultSourcesMissing(Long user, Long world) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from world_summary_sources x left join dreams d on"
                            + " d.id=x.dream_id and d.user_id=x.user_id left join"
                            + " dream_story_versions v on v.id=x.story_version_id where"
                            + " x.world_id=? and x.user_id=? and x.candidate=false and (d.id is"
                            + " null or v.id is null))",
                        Boolean.class,
                        world,
                        user));
    }

    List<Long> publishedWorlds(Long user, Long dream) {
        return jdbc.query(
                "select distinct world_id from world_composition_entries where user_id=? and"
                    + " dream_id=? and candidate=false",
                (r, n) -> r.getLong(1),
                user,
                dream);
    }
}
