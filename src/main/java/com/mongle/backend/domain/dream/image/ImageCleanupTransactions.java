package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 보상 삭제에 실패한 늦은 업로드도 원본/이미지 행을 복원하지 않고 정리 대상으로 전달한다. */
@Service
@RequiredArgsConstructor
public class ImageCleanupTransactions {
    private final ImageCleanupRegistration registration;
    private final UserRepository users;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDiscardedUpload(Long userId, Long imageId, String key) {
        // 동일 키 재전송과 꿈 삭제의 정리 요청 기록을 직렬화한다.
        users.findByIdForUpdate(userId).orElseThrow();
        registration.register(userId, null, imageId, key, ImageCleanupRequest.Reason.DISCARDED_UPLOAD);
    }
}
