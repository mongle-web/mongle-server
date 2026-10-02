package com.mongle.backend.domain.user.service;

import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
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
        user.completeOnboarding(nickname);
        return UserResponse.from(user);
    }
}
