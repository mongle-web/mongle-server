package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ImageCleanupWorkTransactions {
    private final ImageCleanupRequestRepository requests;
    private final DreamImageRepository images;
    private final com.mongle.backend.domain.world.image.WorldImageRepository worldImages;
    private final UserRepository users;
    private final ImageCleanupProperties properties;
    private final Clock authClock;

    public record Claim(Long id, String token, String key) {
        @Override public String toString() { return "ImageCleanupClaim[id=" + id + "]"; }
    }

    public List<Long> candidates(int limit) {
        return requests.candidates(List.of(ImageCleanupRequest.Status.PENDING, ImageCleanupRequest.Status.RETRY_WAIT),
                ImageCleanupRequest.Status.PROCESSING, authClock.instant(), PageRequest.of(0, Math.min(limit, properties.batchSize())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<Claim> claim(Long id) {
        var request = lock(id);
        var now = authClock.instant();
        if (request == null || !request.due(now)) return Optional.empty();
        if (protect(request, now)) return Optional.empty();
        if (request.getAttemptCount() >= properties.maxAttempts()) {
            request.hold("RETRY_EXHAUSTED");
            return Optional.empty();
        }
        request.claim(now, properties.lease());
        requests.flush(); // 삭제 시작 표시는 외부 IO 전에 커밋되어야 한다.
        return Optional.of(new Claim(request.getId(), request.getClaimToken(), request.getStorageKey()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean prepare(Claim claim) {
        var request = lock(claim.id());
        var now = authClock.instant();
        return request != null && request.accepts(claim.token(), now) && !protect(request, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Claim claim) {
        var request = requests.findForUpdate(claim.id()).orElse(null);
        if (request != null && request.accepts(claim.token(), authClock.instant()))
            request.succeed(authClock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Claim claim) {
        var request = requests.findForUpdate(claim.id()).orElse(null);
        if (request == null || !request.accepts(claim.token(), authClock.instant())) return;
        if (request.getAttemptCount() >= properties.maxAttempts()) request.hold("STORAGE_DELETE_FAILED");
        else request.retry(authClock.instant().plus(properties.retryDelay(request.getAttemptCount())), "STORAGE_DELETE_FAILED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void postpone(Claim claim) {
        var request = requests.findForUpdate(claim.id()).orElse(null);
        if (request != null && request.accepts(claim.token(), authClock.instant()))
            request.postponeWithoutAttempt(authClock.instant().plus(properties.retryBaseDelay()));
    }

    private ImageCleanupRequest lock(Long id) {
        // 소유자 조회가 사용자 잠금보다 먼저다. READ_COMMITTED로 잠금 대기 중 공개된 참조도 읽는다.
        var owner = requests.owner(id);
        if (owner.isEmpty()) return null;
        // 이미지 완료/등록과 같은 사용자 → 정리 요청 순서. 탈퇴한 사용자의 파일도 정리할 수 있다.
        users.findByIdForUpdate(owner.get());
        return requests.findForUpdate(id).orElse(null);
    }

    private boolean protect(ImageCleanupRequest request, Instant now) {
        String key = request.getStorageKey();
        if (key.startsWith("world-images/")) {
            if (worldImages.existsByStorageKey(key)) {
                request.retry(now.plus(properties.retryBaseDelay()), "REFERENCED");
                return true;
            }
            var worldImage = worldImages.findByIdAndUserId(request.getImageId(), request.getUserId());
            if (worldImage.isPresent() && worldImage.get().active(now)) {
                String prefix = "world-images/" + request.getUserId() + "/" + request.getImageId() + "/" + worldImage.get().getAttemptId();
                if (key.equals(prefix + ".png") || key.equals(prefix + ".jpg")) {
                    request.retry(now.plus(properties.retryBaseDelay()), "ACTIVE_ATTEMPT");
                    return true;
                }
            }
            return false;
        }
        if (images.existsByStorageKey(key)) {
            request.retry(now.plus(properties.retryBaseDelay()), "REFERENCED");
            return true;
        }
        var image = images.findByIdAndUserId(request.getImageId(), request.getUserId());
        if (image.isPresent() && image.get().active(now)) {
            var row = image.get();
            String prefix = "dream-images/" + request.getUserId() + "/" + request.getImageId() + "/" + row.getAttemptId();
            if (key.equals(prefix + ".png") || key.equals(prefix + ".jpg")) {
                request.retry(now.plus(properties.retryBaseDelay()), "ACTIVE_ATTEMPT");
                return true;
            }
        }
        return false;
    }
}
