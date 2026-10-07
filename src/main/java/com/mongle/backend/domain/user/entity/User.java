package com.mongle.backend.domain.user.entity;

import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.error.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 100)
    private String nickname;

    @Builder(access = AccessLevel.PRIVATE)
    private User(String email, String nickname) {
        this.email = Objects.requireNonNull(email, "이메일은 필수입니다.");
        if (email.isBlank() || email.length() > 255) {
            throw new IllegalArgumentException("이메일은 공백만으로 입력할 수 없으며, 255자 이하여야 합니다.");
        }
        this.nickname = nickname == null ? null : NicknamePolicy.validate(nickname);
    }

    /** 닉네임 입력을 마친 사용자를 생성한다. 기존 도메인 코드에서도 사용한다. */
    public static User create(String email, String nickname) {
        NicknamePolicy.validate(nickname);
        return builder()
                .email(email)
                .nickname(nickname)
                .build();
    }

    /** 소셜 가입 시 닉네임은 비워두고, 온보딩에서 사용자가 직접 입력한다. */
    public static User register(String verifiedEmail) {
        return builder().email(verifiedEmail).build();
    }

    public boolean isOnboardingCompleted() {
        return nickname != null;
    }

    public void completeOnboarding(String nickname) {
        String validated = NicknamePolicy.validate(nickname);
        if (isOnboardingCompleted()) {
            if (this.nickname.equals(validated)) {
                return;
            }
            throw new BusinessException(UserErrorCode.ONBOARDING_ALREADY_COMPLETED);
        }
        this.nickname = validated;
    }
}
