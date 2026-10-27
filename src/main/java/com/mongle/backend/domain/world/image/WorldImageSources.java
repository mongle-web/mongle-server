package com.mongle.backend.domain.world.image;

import com.mongle.backend.domain.dream.image.ImageGenerator;
import com.mongle.backend.domain.dream.story.StoryValidator;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Component @RequiredArgsConstructor
public class WorldImageSources {
    private final JdbcTemplate jdbc;
    private final StoryValidator stories;
    private final WorldImageProperties properties;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Reference(Long dreamId, Long storyVersionId, long sourceRevision, Long bridgeId) {}
    public record Snapshot(List<Reference> references, List<ImageGenerator.WorldStory> stories) {}

    /** Caller holds the user lock. Reject large input before loading complete story JSON. */
    public Snapshot capture(Long user, Long world) {
        Long size = jdbc.queryForObject("select coalesce(sum(char_length(v.result_json)+coalesce(char_length(b.content),0)),0)"
                + " from world_composition_entries e join dream_story_versions v on v.id=e.story_version_id join dream_stories s on s.id=v.story_id and s.user_id=e.user_id"
                + " left join world_bridge_results b on b.id=e.bridge_id and b.user_id=e.user_id"
                + " where e.user_id=? and e.world_id=? and e.candidate=false", Long.class, user, world);
        if (size != null && size > properties.maxInputCharacters()) throw tooLarge();
        var refs = new ArrayList<Reference>(); var input = new ArrayList<ImageGenerator.WorldStory>();
        jdbc.query("select e.dream_id,e.story_version_id,v.source_revision,e.bridge_id,v.result_json,b.content"
                + " from world_composition_entries e join dream_story_versions v on v.id=e.story_version_id join dream_stories s on s.id=v.story_id and s.user_id=e.user_id"
                + " left join world_bridge_results b on b.id=e.bridge_id and b.user_id=e.user_id"
                + " where e.user_id=? and e.world_id=? and e.candidate=false order by e.position", rs -> {
            refs.add(new Reference(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getObject(4,Long.class)));
            input.add(new ImageGenerator.WorldStory(stories.decode(rs.getString(5)).sections(),rs.getString(6)));
        },user,world);
        Long expected = jdbc.queryForObject("select count(*) from world_composition_entries where user_id=? and world_id=? and candidate=false",
                Long.class,user,world);
        if (refs.isEmpty() || expected == null || expected != refs.size())
            throw new BusinessException(WorldImageErrorCode.COMPOSITION_REQUIRED);
        if (JSON.writeValueAsString(input).length() > properties.maxInputCharacters()) throw tooLarge();
        return new Snapshot(List.copyOf(refs),List.copyOf(input));
    }
    private BusinessException tooLarge() { return new BusinessException(WorldImageErrorCode.INPUT_TOO_LARGE); }
}
