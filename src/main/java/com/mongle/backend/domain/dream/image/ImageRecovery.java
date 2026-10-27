package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.credit.CreditReservationRecovery;
import com.mongle.backend.global.common.GenerationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;

/** 스캔과 각 시도의 짧은 트랜잭션을 분리한다. 외부 IO나 AI 재실행은 없다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImageRecovery {
    private final DreamImageRepository images;
    private final ImageTransactions transactions;
    private final com.mongle.backend.domain.world.image.WorldImageRepository worldImages;
    private final com.mongle.backend.domain.world.image.WorldImageTransactions worldTransactions;
    private final CreditReservationRecovery credits;
    private final ImageRecoveryProperties properties;
    private final Clock authClock;

    @Transactional(propagation = Propagation.NEVER)
    public int recoverExpired() {
        int recovered = 0;
        var candidates = images.expiredAttempts(GenerationStatus.PROCESSING, authClock.instant(),
                PageRequest.of(0, properties.batchSize()));
        for (var candidate : candidates) {
            try {
                if (transactions.expire(candidate.getUserId(), candidate.getImageId())) recovered++;
            } catch (RuntimeException ex) {
                log.warn("이미지 시도 예약을 복구하지 못했습니다. 다음 주기에 다시 확인합니다.");
            }
        }
        for (var candidate : worldImages.expiredAttempts(GenerationStatus.PROCESSING, authClock.instant(),
                PageRequest.of(0, properties.batchSize()))) {
            try {
                if (worldTransactions.expire(candidate.getUserId(), candidate.getImageId())) recovered++;
            } catch (RuntimeException ex) {
                log.warn("세계관 이미지 시도 예약을 복구하지 못했습니다. 다음 주기에 다시 확인합니다.");
            }
        }
        // 삭제된 이미지 등 연결이 사라진 과거 예약도 영속 원장에서 회수한다.
        credits.recoverExpired(properties.batchSize());
        return recovered;
    }
}
