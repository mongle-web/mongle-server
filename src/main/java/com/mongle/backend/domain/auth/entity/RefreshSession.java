package com.mongle.backend.domain.auth.entity;

import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

@Getter
@Entity
@Table(name = "refresh_sessions", uniqueConstraints =
        @UniqueConstraint(name = "uk_refresh_sessions_token_hash", columnNames = "token_hash"),
        indexes = @Index(name = "idx_refresh_sessions_user_id", columnList = "user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshSession extends BaseCreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    // 사용한 토큰의 해시를 세션 만료까지 보관해 이전 토큰 재사용을 탐지한다.
    @ElementCollection
    @CollectionTable(name = "refresh_session_tokens", joinColumns = @JoinColumn(name = "session_id", nullable = false),
            uniqueConstraints = @UniqueConstraint(name = "uk_refresh_session_tokens_hash", columnNames = "token_hash"))
    @Column(name = "token_hash", nullable = false, length = 64)
    private Set<String> tokenHashes = new HashSet<>();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Builder(access = AccessLevel.PRIVATE)
    private RefreshSession(User user, String tokenHash, LocalDateTime expiresAt) {
        this.user = Objects.requireNonNull(user, "사용자는 필수입니다.");
        this.tokenHash = Objects.requireNonNull(tokenHash, "토큰 해시는 필수입니다.");
        this.tokenHashes.add(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt, "만료 시각은 필수입니다.");
    }

    public static RefreshSession create(User user, String tokenHash, LocalDateTime expiresAt) {
        return builder().user(user).tokenHash(tokenHash).expiresAt(expiresAt).build();
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void rotate(String tokenHash) {
        // 로그인 시 정한 만료 시각은 늘리지 않고 토큰 값만 교체한다.
        this.tokenHash = Objects.requireNonNull(tokenHash, "토큰 해시는 필수입니다.");
        this.tokenHashes.add(tokenHash);
    }
}
