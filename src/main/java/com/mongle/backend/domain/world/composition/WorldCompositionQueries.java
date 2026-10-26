package com.mongle.backend.domain.world.composition;

import com.mongle.backend.domain.dream.story.StoryValidator;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Repository @RequiredArgsConstructor
public class WorldCompositionQueries {
    private final JdbcTemplate jdbc;
    private final StoryValidator stories;
    record Source(Long dreamId,Long versionId) {}
    private static final String CURRENT_JOINS=" from dreams d left join dream_analyses a on a.source_dream_id=d.id and a.user_id=d.user_id"
            +" left join dream_stories s on s.analysis_id=a.id and s.user_id=d.user_id"
            +" left join dream_story_versions v on v.story_id=s.id and v.generation_key=s.attempt_id"
            +" and v.source_revision=d.source_revision and s.result_revision=d.source_revision"
            +" and a.observed_revision=d.source_revision and a.status='COMPLETED' and s.status='COMPLETED'";
    List<Source> sources(Long user,Long world) {
        return jdbc.query("select d.id,v.id as version_id"+CURRENT_JOINS
                +" join world_selections selected on selected.dream_id=d.id and selected.world_id=?"
                +" where d.user_id=? and d.record_status='COMPLETED' order by d.dreamed_at,d.id",
                (r,n)->new Source(r.getLong(1),r.getObject(2,Long.class)),world,user);
    }
    Long currentVersion(Long user,Long dream) {
        var rows=jdbc.query("select v.id"+CURRENT_JOINS+" where d.user_id=? and d.id=? and d.record_status='COMPLETED'",
                (r,n)->r.getObject(1,Long.class),user,dream);
        return rows.isEmpty()?null:rows.getFirst();
    }
    boolean selectionChanged(Long user,Long world) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select ("
                +" (select count(*) from world_selections where world_id=?) <> (select count(*) from world_composition_entries where world_id=? and candidate=false)"
                +" or exists(select 1 from world_selections s where s.world_id=? and not exists(select 1 from world_composition_entries e where e.world_id=s.world_id and e.candidate=false and e.dream_id=s.dream_id)))",
                Boolean.class,world,world,world));
    }
    boolean sourcesValid(WorldComposition w) {
        var rows=jdbc.query("select e.dream_id,e.story_version_id,fixed.id as fixed_id,d.id as live_id,current_v.id as current_id"
                +" from world_composition_entries e left join dream_story_versions fixed on fixed.id=e.story_version_id"
                +" left join dreams d on d.id=e.dream_id and d.user_id=e.user_id"
                +" left join dream_analyses a on a.source_dream_id=d.id and a.user_id=e.user_id"
                +" left join dream_stories s on s.analysis_id=a.id and s.user_id=e.user_id"
                +" left join dream_story_versions current_v on current_v.story_id=s.id and current_v.generation_key=s.attempt_id"
                +" and current_v.source_revision=d.source_revision and s.result_revision=d.source_revision"
                +" and a.observed_revision=d.source_revision and a.status='COMPLETED' and s.status='COMPLETED'"
                +" where e.world_id=? and e.candidate=true order by e.position",
                (r,n)->new Long[]{r.getLong(1),r.getLong(2),r.getObject(3,Long.class),r.getObject(4,Long.class),r.getObject(5,Long.class)},w.getWorldId());
        if(rows.isEmpty())return false;
        for(var r:rows) {
            if(r[2]==null||r[3]==null)return false;
            if(("BUILD".equals(w.getRequestType())||Objects.equals(w.getTargetDreamId(),r[0]))&&!Objects.equals(r[1],r[4]))return false;
        }
        return true;
    }
    WorldEntryPage page(Long user,WorldComposition w,String cursor,int size) {
        if(size<1||size>50) throw new BusinessException(WorldCompositionErrorCode.INVALID_PAGE);
        long after=-1;
        if(cursor!=null) {
            try {
                if(cursor.length()>160) throw new IllegalArgumentException();
                String raw=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.US_ASCII);
                if(!raw.matches("[1-9][0-9]*:[0-9]+:[0-9]+")) throw new IllegalArgumentException();
                var values=raw.split(":");
                if(Long.parseLong(values[0])!=w.getWorldId()) throw new IllegalArgumentException();
                if(Long.parseLong(values[1])!=w.getCompositionRevision()) throw new BusinessException(WorldCompositionErrorCode.CONFLICT);
                after=Long.parseLong(values[2]);
            } catch(BusinessException ex) {throw ex;} catch(RuntimeException ex) {throw new BusinessException(WorldCompositionErrorCode.INVALID_PAGE);}
        }
        var rows=jdbc.query("select e.position,e.dream_id,d.dreamed_at,e.story_version_id,v.result_json,e.bridge_id,b.content,"
                +" (d.source_revision<>v.source_revision) as changed,"
                +" exists(select 1 from dream_story_versions latest where latest.story_id=v.story_id and latest.id>v.id) as newer,"
                +" exists(select 1 from world_composition_entries previous where previous.world_id=e.world_id and previous.candidate=false and previous.position<e.position) as has_previous"
                +" from world_composition_entries e join dream_story_versions v on v.id=e.story_version_id"
                +" join dreams d on d.id=e.dream_id and d.user_id=e.user_id"
                +" left join world_bridge_results b on b.id=e.bridge_id and b.user_id=e.user_id"
                +" where e.world_id=? and e.user_id=? and e.candidate=false and e.position>? order by e.position limit ?",
                (r,n)->new Positioned(r.getLong("position"),new WorldEntryPage.Entry(r.getLong("dream_id"),r.getDate("dreamed_at").toLocalDate(),
                        r.getLong("story_version_id"),stories.decode(r.getString("result_json")).sections(),r.getObject("bridge_id",Long.class),
                        r.getString("content"),r.getBoolean("has_previous")&&r.getObject("bridge_id")==null,r.getBoolean("changed"),r.getBoolean("newer"))),w.getWorldId(),user,after,size+1);
        boolean more=rows.size()>size;var page=rows.stream().limit(size).toList();
        String next=more?Base64.getUrlEncoder().withoutPadding().encodeToString((w.getWorldId()+":"+w.getCompositionRevision()+":"+page.getLast().position()).getBytes(StandardCharsets.US_ASCII)):null;
        return new WorldEntryPage(w.getWorldId(),w.getCompositionRevision(),page.stream().map(Positioned::entry).toList(),more,next);
    }
    private record Positioned(long position,WorldEntryPage.Entry entry) {}
}
