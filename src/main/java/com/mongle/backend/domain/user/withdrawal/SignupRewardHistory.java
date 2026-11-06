package com.mongle.backend.domain.user.withdrawal;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name="signup_reward_history")
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SignupRewardHistory {
    @Id @Column(name="identity_hash",length=64) private String identityHash;
    @Column(name="benefit",nullable=false,length=30) private String benefit;
    @Column(name="granted_at",nullable=false) private LocalDateTime grantedAt;
    public static SignupRewardHistory create(String hash, LocalDateTime time) {
        var h = new SignupRewardHistory(); h.identityHash=hash; h.benefit="INITIAL_SIGNUP"; h.grantedAt=time; return h;
    }
}
