package com.mongle.backend.domain.user.withdrawal;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.*;

@Entity @Table(name="social_unlink_tasks",indexes=@Index(name="idx_unlink_due",columnList="state,next_run_at"))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SocialUnlinkTask {
    public enum State { PENDING, PROCESSING, RETRY_WAIT, COMPLETED, CANCELLED }
    @Id @Column(name="identity_hash",length=64) private String identityHash;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(name="encrypted_provider_id",length=1024) private String encryptedProviderId;
    @Enumerated(EnumType.STRING) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(nullable=false,length=20) private State state;
    @Column(name="attempt_count",nullable=false) private int attemptCount;
    @Column(name="claim_token",length=36) private String claimToken;
    @Column(name="next_run_at",nullable=false) private LocalDateTime nextRunAt;
    @Column(name="failure_code",length=40) private String failureCode;
    @Version private long version;

    public static SocialUnlinkTask create(String hash, Long user, String encrypted, LocalDateTime now) {
        var t=new SocialUnlinkTask(); t.identityHash=hash; t.enqueue(user,encrypted,now); return t;
    }
    public void enqueue(Long user, String encrypted, LocalDateTime now) {
        userId=user; encryptedProviderId=encrypted; state=State.PENDING; attemptCount=0;
        claimToken=null; failureCode=null; nextRunAt=now;
    }
    public boolean rejoinBlocked() {
        return state==State.PROCESSING || state==State.RETRY_WAIT;
    }
    public void cancelUnstarted() {
        if (state==State.PENDING && attemptCount==0) {
            state=State.CANCELLED; encryptedProviderId=null; failureCode=null; claimToken=null;
        }
    }
    public boolean due(LocalDateTime now) {
        return (state==State.PENDING || state==State.RETRY_WAIT || state==State.PROCESSING)
                && !nextRunAt.isAfter(now);
    }
    public String claim(LocalDateTime now) {
        state=State.PROCESSING; attemptCount++; claimToken=UUID.randomUUID().toString();
        // HTTP deadline보다 충분히 긴 재시도 시점. 불확실한 외부 작업 종료 전 새 앱 계정을 발급하지 않는다.
        nextRunAt=now.plusMinutes(2); return claimToken;
    }
    public void complete(String token) {
        if (!Objects.equals(token,claimToken) || state!=State.PROCESSING) return;
        state=State.COMPLETED; encryptedProviderId=null; claimToken=null; failureCode=null;
    }
    public void retry(String token, String code, LocalDateTime now) {
        if (!Objects.equals(token,claimToken) || state!=State.PROCESSING) return;
        state=State.RETRY_WAIT; claimToken=null; failureCode=code;
        nextRunAt=now.plusSeconds(Math.min(3600,30L*(1L<<Math.min(attemptCount,6))));
    }
    @Override public String toString() { return "SocialUnlinkTask[state="+state+", attempts="+attemptCount+"]"; }
}
