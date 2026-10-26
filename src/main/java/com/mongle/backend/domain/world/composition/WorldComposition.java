package com.mongle.backend.domain.world.composition;
import com.mongle.backend.global.common.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.util.*;
@Getter @Entity @Table(name="world_compositions",indexes=@Index(name="idx_composition_work",columnList="status,world_id"))
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class WorldComposition extends BaseEntity {
    @Id @Column(name="world_id") private Long worldId;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(name="composition_revision",nullable=false) private long compositionRevision;
    @Column(name="has_result",nullable=false) private boolean hasResult;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=30)
    private GenerationStatus status=GenerationStatus.PENDING;
    @Column(name="attempt_id",length=36) private String attemptId;
    @Column(name="folder_revision",nullable=false) private long folderRevision;
    @Column(name="base_revision",nullable=false) private long baseRevision;
    @Column(name="request_type",length=10) private String requestType;
    @Column(name="target_dream_id") private Long targetDreamId;
    @Column(name="target_version_id") private Long targetVersionId;
    @Column(name="settings_json",columnDefinition="TEXT") private String settingsJson;
    @Column(name="prompt_version",length=50) private String promptVersion;
    @Column(nullable=false) private boolean retrying;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name="lease_until") private Instant leaseUntil;
    @Column(name="claim_token",length=36) private String claimToken;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name="claim_until") private Instant claimUntil;
    @Column(name="failure_code",length=50) private String failureCode;
    @Version @Column(nullable=false) private long version;
    static WorldComposition create(Long worldId,Long userId) { var w=new WorldComposition(); w.worldId=worldId;w.userId=userId;return w; }
    boolean working() { return status==GenerationStatus.PENDING || status==GenerationStatus.PROCESSING; }
    boolean active(Instant now) { return working() && leaseUntil!=null && leaseUntil.isAfter(now); }
    void start(long folder,String type,Long dream,Long story,String settings,String prompt,Instant now) {
        folderRevision=folder;baseRevision=compositionRevision;requestType=type;targetDreamId=dream;targetVersionId=story;
        settingsJson=settings;promptVersion=prompt;retrying=false;restart(now);
    }
    void restart(Instant now) { attemptId=UUID.randomUUID().toString();status=GenerationStatus.PENDING;failureCode=null;claimToken=null;claimUntil=null;leaseUntil=now.plusSeconds(180); }
    void retry(Instant now) { restart(now);retrying=true; }
    void claim(Instant now) { claimToken=UUID.randomUUID().toString();claimUntil=now.plusSeconds(120);leaseUntil=now.plusSeconds(180);status=GenerationStatus.PROCESSING; }
    boolean accepts(String attempt,String token,Instant now) { return active(now)&&Objects.equals(attemptId,attempt)&&Objects.equals(claimToken,token)&&claimUntil!=null&&claimUntil.isAfter(now); }
    void advance(Instant now) { claimToken=null;claimUntil=null;leaseUntil=now.plusSeconds(180); }
    void complete() { hasResult=true;compositionRevision++;status=GenerationStatus.COMPLETED;leaseUntil=null;claimToken=null;claimUntil=null;failureCode=null; }
    void fail(String code) { status=GenerationStatus.FAILED;failureCode=code;leaseUntil=null;claimToken=null;claimUntil=null; }
    void sourceDeleted() { compositionRevision++; if(working()) fail("SOURCE_DELETED"); }
}
