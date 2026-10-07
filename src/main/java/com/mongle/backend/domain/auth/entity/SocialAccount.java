package com.mongle.backend.domain.auth.entity;

import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.regex.Pattern;

@Getter
@Entity
@Table(name = "social_accounts", uniqueConstraints = @UniqueConstraint(
        name = "uk_social_accounts_provider_user_id",
        columnNames = {"provider", "provider_user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialAccount extends BaseEntity {

    private static final Pattern PROVIDER_ID = Pattern.compile("[\\x21-\\x7E]{1,255}");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    private SocialProvider provider;

    @Convert(converter = ProviderUserIdConverter.class)
    @Column(name = "provider_user_id", nullable = false, length = 255,
            columnDefinition = "VARBINARY(255)")
    private String providerUserId;

    @Builder(access = AccessLevel.PRIVATE)
    private SocialAccount(User user, SocialProvider provider, String providerUserId) {
        this.user = Objects.requireNonNull(user, "사용자는 필수입니다.");
        this.provider = Objects.requireNonNull(provider, "소셜 로그인 제공사는 필수입니다.");
        if (providerUserId == null || !PROVIDER_ID.matcher(providerUserId).matches()) {
            throw new IllegalArgumentException("소셜 계정 ID는 공백을 제외한 출력 가능한 ASCII 문자로 1~255자여야 합니다.");
        }
        this.providerUserId = providerUserId;
    }

    public static SocialAccount link(User user, SocialProvider provider, String providerUserId) {
        return builder().user(user).provider(provider).providerUserId(providerUserId).build();
    }
}
