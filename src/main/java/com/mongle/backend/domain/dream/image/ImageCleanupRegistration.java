package com.mongle.backend.domain.dream.image;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;

/** 호출자는 사용자 행을 먼저 잠근다. 꿈 삭제와 보상 실패가 같은 등록 계약을 사용한다. */
@Service
@RequiredArgsConstructor
public class ImageCleanupRegistration {
    private final ImageCleanupRequestRepository requests;
    private final Clock authClock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void register(Long userId, Long dreamId, Long imageId, String key, ImageCleanupRequest.Reason reason) {
        var prior = requests.findKeyForUpdate(key);
        if (prior.isPresent()) {
            var request = prior.get();
            if (!request.getUserId().equals(userId) || !request.getImageId().equals(imageId))
                throw new IllegalStateException("파일 정리 대상의 소유권이 일치하지 않습니다.");
            request.requestedAgain(authClock.instant());
        } else {
            requests.save(ImageCleanupRequest.create(userId, dreamId, imageId, key, reason, authClock.instant()));
        }
        requests.flush();
    }
}
