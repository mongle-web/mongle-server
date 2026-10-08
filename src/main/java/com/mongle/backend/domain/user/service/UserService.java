package com.mongle.backend.domain.user.service;

import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.logging.CommittedLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserResponse getMe(Long authenticatedUserId) {
        return UserResponse.from(userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND)));
    }

    @Transactional
    public UserResponse completeOnboarding(Long authenticatedUserId, String nickname) {
        // 온보딩 요청이 동시에 들어와도 이미 저장된 닉네임이
        // 다른 요청의 닉네임으로 덮어써지지 않도록 잠금을 건다.
        User user = userRepository.findByIdForUpdate(authenticatedUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        boolean alreadyCompleted = user.isOnboardingCompleted();
        user.completeOnboarding(nickname);
        // 같은 닉네임의 멱등 재요청은 새 상태 변경이 아니다. 최초 커밋에만 성공 로그를 남긴다.
        if (!alreadyCompleted) {
            CommittedLog.afterCommit(() -> log.info("사용자 온보딩 완료: userId={}", authenticatedUserId));
        }
        return UserResponse.from(user);
    }
}
