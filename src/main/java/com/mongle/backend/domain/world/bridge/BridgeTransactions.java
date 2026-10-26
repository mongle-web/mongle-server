package com.mongle.backend.domain.world.bridge;

import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.domain.dream.gateway.DreamGenerationSettings;
import com.mongle.backend.domain.dream.story.StoryResultVersion;
import com.mongle.backend.domain.dream.story.StoryResultVersionRepository;
import com.mongle.backend.domain.dream.story.StoryValidator;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BridgeTransactions {
    private final UserRepository users;
    private final StoryResultVersionRepository versions;
    private final BridgeResultRepository bridges;
    private final StoryValidator stories;
    private final DreamGenerationSettings settings;
    private final Clock authClock;

    public record Reservation(BridgeResponse response, BridgeGenerator.Input input) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation begin(Long userId, BridgeRequest request, boolean available) {
        var user = lock(userId);
        if (request == null
                || !positive(request.firstStoryVersionId())
                || !positive(request.secondStoryVersionId())) {
            throw new BusinessException(BridgeErrorCode.INVALID_PAIR);
        }
        var first = selected(userId, request.firstStoryVersionId());
        var second = selected(userId, request.secondStoryVersionId());
        var firstDream = first.getStory().getAnalysis().getDream();
        var secondDream = second.getStory().getAnalysis().getDream();
        if (firstDream.getId().equals(secondDream.getId())
                || firstDream.getDreamedAt().equals(secondDream.getDreamedAt())) {
            throw new BusinessException(BridgeErrorCode.INVALID_PAIR);
        }
        var before =
                firstDream.getDreamedAt().isBefore(secondDream.getDreamedAt()) ? first : second;
        var after = before == first ? second : first;
        String captured = settings.capture();
        String hash = hash(captured);
        var prior =
                bridges
                        .findByUserIdAndBeforeVersionIdAndAfterVersionIdAndPromptVersionAndSettingsHash(
                                userId, before.getId(), after.getId(), BridgePrompt.VERSION, hash);
        if (prior.isPresent()) {
            // 실패/만료도 재전송으로 자동 재시도하지 않는다. retry를 명시적으로 호출해야 한다.
            return new Reservation(response(prior.get()), null);
        }
        requireAvailable(available);
        String beforeStory = text(before);
        String afterStory = text(after);
        var bridge =
                bridges.saveAndFlush(
                        BridgeResult.create(
                                user, before, after, captured, hash, authClock.instant()));
        return reservation(bridge, beforeStory, afterStory);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation retry(Long userId, Long bridgeId, Long expectedVersion, boolean available) {
        lock(userId);
        var bridge = owned(userId, bridgeId);
        if (bridge.getStatus() == GenerationStatus.COMPLETED
                || bridge.active(authClock.instant())) {
            return new Reservation(response(bridge), null);
        }
        if (expectedVersion == null || expectedVersion != bridge.getVersion()) {
            throw new BusinessException(BridgeErrorCode.VERSION_CONFLICT);
        }
        // 재시도에서 다른 설정으로 생성하고 옛 키에 저장하지 않는다. 설정 변경 시 generate로 새 키를 요청한다.
        if (!BridgePrompt.VERSION.equals(bridge.getPromptVersion())
                || !settings.capture().equals(bridge.getSettingsJson())) {
            throw new BusinessException(BridgeErrorCode.SETTINGS_CHANGED);
        }
        var before = selected(userId, bridge.getBeforeVersion().getId());
        var after = selected(userId, bridge.getAfterVersion().getId());
        requireAvailable(available);
        String beforeStory = text(before);
        String afterStory = text(after);
        bridge.start(authClock.instant());
        bridges.flush();
        return reservation(bridge, beforeStory, afterStory);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BridgeResponse finish(BridgeGenerator.Input input, String content) {
        lock(input.userId());
        var row = owned(input.userId(), input.bridgeId());
        if (!row.accepts(input.attemptId())) return response(row);
        if (!row.active(authClock.instant())) row.fail("ATTEMPT_EXPIRED");
        else row.succeed(content);
        bridges.flush();
        return response(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BridgeResponse fail(BridgeGenerator.Input input, String code) {
        lock(input.userId());
        var row = owned(input.userId(), input.bridgeId());
        if (row.accepts(input.attemptId())) {
            row.fail(row.active(authClock.instant()) ? code : "ATTEMPT_EXPIRED");
            bridges.flush();
        }
        return response(row);
    }

    public BridgeResponse get(Long userId, Long bridgeId) {
        return response(owned(userId, bridgeId));
    }

    private Reservation reservation(BridgeResult row, String before, String after) {
        return new Reservation(
                response(row),
                new BridgeGenerator.Input(
                        row.getUser().getId(), row.getId(), row.getAttemptId(), before, after));
    }

    private StoryResultVersion selected(Long userId, Long versionId) {
        var result =
                versions.findOwnedLive(versionId, userId)
                        .orElseThrow(() -> new BusinessException(BridgeErrorCode.NOT_FOUND));
        if (result.getStory().getAnalysis().getDream().getRecordStatus()
                != DreamRecordStatus.COMPLETED) {
            throw new BusinessException(BridgeErrorCode.INVALID_PAIR);
        }
        return result;
    }

    private String text(StoryResultVersion version) {
        var sections = stories.decode(version.getResultJson()).sections();
        if (sections.isEmpty()) throw new BusinessException(BridgeErrorCode.INVALID_PAIR);
        return sections.stream()
                .map(section -> section.content())
                .collect(Collectors.joining("\n\n"));
    }

    private BridgeResult owned(Long userId, Long id) {
        if (userId == null || !positive(id)) throw new BusinessException(BridgeErrorCode.NOT_FOUND);
        return bridges.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(BridgeErrorCode.NOT_FOUND));
    }

    private User lock(Long userId) {
        if (userId == null) throw new BusinessException(BridgeErrorCode.NOT_FOUND);
        var user =
                users.findByIdForUpdate(userId)
                        .orElseThrow(() -> new BusinessException(BridgeErrorCode.NOT_FOUND));
        if (!user.isOnboardingCompleted())
            throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
        return user;
    }

    private BridgeResponse response(BridgeResult row) {
        boolean expired =
                row.getStatus() == GenerationStatus.PROCESSING && !row.active(authClock.instant());
        return new BridgeResponse(
                row.getId(),
                row.getBeforeDream().getId(),
                row.getAfterDream().getId(),
                row.getBeforeVersion().getId(),
                row.getAfterVersion().getId(),
                row.getBeforeDream().getDreamedAt(),
                row.getAfterDream().getDreamedAt(),
                row.getPromptVersion(),
                settings.decode(row.getSettingsJson()),
                expired ? GenerationStatus.FAILED : row.getStatus(),
                expired ? "ATTEMPT_EXPIRED" : row.getFailureCode(),
                row.getContent(),
                row.getVersion(),
                row.getLeaseUntil());
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static void requireAvailable(boolean available) {
        if (!available) throw new BusinessException(BridgeErrorCode.UNAVAILABLE);
    }

    private static String hash(String captured) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(captured.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
