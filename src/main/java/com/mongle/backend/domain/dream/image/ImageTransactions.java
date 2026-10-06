package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.dream.analysis.StoredAnalysisRepository;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ImageTransactions {
    private final UserRepository users;
    private final DreamRepository dreams;
    private final StoredAnalysisRepository analyses;
    private final DreamStoryRepository stories;
    private final StoryValidator storyValidator;
    private final DreamImageRepository images;
    private final ImageOptions options;
    private final Clock authClock;

    public record Reservation(ImageResponse response, ImageGenerator.Input input) {}

    public record Completion(ImageResponse response, boolean stored) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation begin(Long userId, Long dreamId, ImageRequest request, boolean available) {
        lock(userId);
        var dream =
                dreams.findByIdAndUserId(dreamId, userId)
                        .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
        dream.checkRevision(request.revision());
        if (dream.getRecordStatus() != DreamRecordStatus.COMPLETED)
            throw new BusinessException(DreamErrorCode.INVALID_STATE);
        var analysis =
                analyses.findBySourceDreamIdAndUserId(dreamId, userId)
                        .orElseThrow(() -> new BusinessException(ImageErrorCode.STORY_REQUIRED));
        var story =
                stories.findByAnalysisIdAndUserId(analysis.getId(), userId)
                        .filter(
                                s ->
                                        s.getStatus() == GenerationStatus.COMPLETED
                                                && s.getResultJson() != null
                                                && Objects.equals(
                                                        s.getResultRevision(),
                                                        dream.getSourceRevision())
                                                && analysis.getStatus()
                                                        == GenerationStatus.COMPLETED
                                                && analysis.getObservedRevision()
                                                        == dream.getSourceRevision())
                        .orElseThrow(() -> new BusinessException(ImageErrorCode.STORY_REQUIRED));
        options.requireAllowed(request.style(), request.mood());
        String hash = hash(story.getResultJson());
        var prior = images.findByAnalysisIdAndUserId(analysis.getId(), userId);
        if (prior.isPresent() && prior.get().active(authClock.instant())) {
            if (!sameOptions(prior.get(), request)
                    || prior.get().getSourceRevision() != dream.getSourceRevision()
                    || !hash.equals(prior.get().getStoryHash()))
                throw new BusinessException(ImageErrorCode.VERSION_CONFLICT);
            return new Reservation(response(prior.get()), null);
        }
        if (prior.isPresent()
                && prior.get().getResultStoryHash() != null
                && !request.regenerate()) {
            var previous = prior.get();
            // 완료 결과는 재사용하고, 실패/만료 후에는 마지막 시도와 같은 입력만 재시도한다.
            boolean completed = previous.getStatus() == GenerationStatus.COMPLETED;
            boolean sameInput =
                    Objects.equals(
                                    completed ? previous.getResultStyle() : previous.getStyle(),
                                    request.style())
                            && Objects.equals(
                                    completed ? previous.getResultMood() : previous.getMood(),
                                    request.mood())
                            && Objects.equals(
                                    completed
                                            ? previous.getResultRevision()
                                            : previous.getSourceRevision(),
                                    dream.getSourceRevision())
                            && hash.equals(
                                    completed
                                            ? previous.getResultStoryHash()
                                            : previous.getStoryHash());
            if (!sameInput) throw new BusinessException(ImageErrorCode.REGENERATION_REQUIRED);
            if (completed) return new Reservation(response(previous), null);
        }
        if (request.regenerate()
                && (prior.isEmpty()
                        || request.imageVersion() == null
                        || request.imageVersion() != prior.get().getVersion()))
            throw new BusinessException(ImageErrorCode.VERSION_CONFLICT);
        if (!available) throw new BusinessException(ImageErrorCode.UNAVAILABLE);
        var image = prior.orElseGet(() -> DreamImage.create(analysis));
        image.start(
                dream.getSourceRevision(),
                hash,
                request.style(),
                request.mood(),
                authClock.instant());
        images.saveAndFlush(image);
        var input =
                new ImageGenerator.Input(
                        userId,
                        image.getId(),
                        image.getAttemptId(),
                        DreamImage.PROMPT_VERSION,
                        image.getStyle(),
                        image.getMood(),
                        storyValidator.decode(story.getResultJson()).sections());
        return new Reservation(response(image), input);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Completion finish(ImageGenerator.Input input, String key, ImagePayload payload) {
        lock(input.userId());
        var image = owned(input.userId(), input.imageId());
        if (!image.accepts(input.attemptId())) return new Completion(response(image), false);
        String failure = sourceFailure(image);
        if (failure != null) image.fail(failure);
        else if (!image.active(authClock.instant())) image.fail("ATTEMPT_EXPIRED");
        else image.finish(key, payload);
        images.flush();
        return new Completion(response(image), image.getStatus() == GenerationStatus.COMPLETED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImageResponse fail(ImageGenerator.Input input, String code) {
        lock(input.userId());
        var image = owned(input.userId(), input.imageId());
        if (!image.accepts(input.attemptId())) return response(image);
        String failure = sourceFailure(image);
        image.fail(failure == null ? code : failure);
        images.flush();
        return response(image);
    }

    public ImageResponse get(Long userId, Long imageId) {
        return response(owned(userId, imageId));
    }

    public ImageResponse latest(Long userId, Long dreamId) {
        dreams.findByIdAndUserId(dreamId, userId)
                .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
        var analysis =
                analyses.findBySourceDreamIdAndUserId(dreamId, userId)
                        .orElseThrow(() -> new BusinessException(ImageErrorCode.NOT_FOUND));
        return response(
                images.findByAnalysisIdAndUserId(analysis.getId(), userId)
                        .orElseThrow(() -> new BusinessException(ImageErrorCode.NOT_FOUND)));
    }

    public String assetKey(Long userId, Long imageId) {
        String key = owned(userId, imageId).getStorageKey();
        if (key == null) throw new BusinessException(ImageErrorCode.NOT_FOUND);
        return key;
    }

    private DreamImage owned(Long userId, Long imageId) {
        return images.findByIdAndUserId(imageId, userId)
                .orElseThrow(() -> new BusinessException(ImageErrorCode.NOT_FOUND));
    }

    private void lock(Long userId) {
        var user =
                users.findByIdForUpdate(userId)
                        .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
        if (!user.isOnboardingCompleted())
            throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }

    private String sourceFailure(DreamImage image) {
        var dream = image.getAnalysis().getDream();
        if (dream == null) return "SOURCE_DELETED";
        if (dream.getSourceRevision() != image.getSourceRevision()) return "SOURCE_CHANGED";
        var story =
                stories.findByAnalysisIdAndUserId(
                        image.getAnalysis().getId(), image.getUser().getId());
        if (story.isEmpty()
                || story.get().getStatus() != GenerationStatus.COMPLETED
                || !image.getStoryHash().equals(hash(story.get().getResultJson())))
            return "STORY_CHANGED";
        return null;
    }

    private boolean sameOptions(DreamImage image, ImageRequest request) {
        return Objects.equals(image.getStyle(), request.style())
                && Objects.equals(image.getMood(), request.mood());
    }

    private ImageResponse response(DreamImage image) {
        var analysis = image.getAnalysis();
        var dream = analysis.getDream();
        var story = stories.findByAnalysisIdAndUserId(analysis.getId(), image.getUser().getId());
        String visibleHash =
                image.getResultStoryHash() == null
                        ? image.getStoryHash()
                        : image.getResultStoryHash();
        long revision =
                image.getResultRevision() == null
                        ? image.getSourceRevision()
                        : image.getResultRevision();
        boolean changed =
                story.isPresent()
                        && story.get().getResultJson() != null
                        && !Objects.equals(visibleHash, hash(story.get().getResultJson()));
        return new ImageResponse(
                image.getId(),
                analysis.getId(),
                dream == null ? null : dream.getId(),
                analysis.getDreamedAt(),
                image.getVersion(),
                image.getStatus(),
                image.getFailureCode(),
                image.getStyle(),
                image.getMood(),
                dream == null,
                dream != null && dream.getSourceRevision() != revision,
                changed,
                image.getStorageKey() != null && image.getStatus() != GenerationStatus.COMPLETED,
                image.getResultRevision(),
                image.getResultStyle(),
                image.getResultMood(),
                image.getContentType(),
                image.getWidth(),
                image.getHeight());
    }

    private static String hash(String text) {
        if (text == null) return null;
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
