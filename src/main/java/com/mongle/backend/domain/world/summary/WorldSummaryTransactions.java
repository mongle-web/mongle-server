package com.mongle.backend.domain.world.summary;

import com.mongle.backend.domain.dream.gateway.DreamGenerationSettings;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.world.composition.*;
import com.mongle.backend.domain.world.folder.*;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldSummaryTransactions {
    private final UserRepository users;
    private final WorldFolderRepository folders;
    private final WorldCompositionRepository compositions;
    private final WorldSummaryRepository summaries;
    private final WorldSummarySourceRepository sources;
    private final WorldSummaryQueries queries;
    private final WorldSummaryProperties properties;
    private final WorldSummaryGenerator generator;
    private final WorldSummaryValidator validator;
    private final DreamGenerationSettings settings;
    private final Clock authClock;
    private final JsonMapper json = JsonMapper.builder().build();

    public record Step(
            Long userId, Long worldId, String attemptId, WorldSummaryEvidence.Data data) {
        @Override
        public String toString() {
            return "WorldSummaryStep[worldId=" + worldId + "]";
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public WorldSummaryResponse get(Long user, Long world) {
        lock(user);
        folder(user, world);
        var w = compositions.findByWorldIdAndUserId(world, user).orElse(null);
        var s = summaries.findByWorldIdAndUserId(world, user).orElse(null);
        sanitize(s);
        summaries.flush();
        return response(user, world, w, s);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public WorldSummaryResponse request(Long user, Long world) {
        lock(user);
        folder(user, world);
        var w = compositions.findByWorldIdAndUserId(world, user).orElse(null);
        if (w == null || !w.isHasResult())
            throw new BusinessException(WorldSummaryErrorCode.REQUIRED);
        var s = summaries.findByWorldIdAndUserId(world, user).orElse(null);
        sanitize(s);
        var stats = queries.stats(user, world);
        if (stats.count() == 0) return response(user, world, w, s);
        boolean compositionBusy = compositionBusy(w);
        if (!compositionBusy
                && s != null
                && (s.matches(w.getCompositionRevision(), settings.capture())
                        || s.getRequestedRevision() == w.getCompositionRevision()
                                && (s.getStatus() == GenerationStatus.PENDING
                                        || s.active(authClock.instant())))) {
            summaries.flush();
            return response(user, world, w, s);
        }
        if (compositionBusy) throw new BusinessException(WorldSummaryErrorCode.BUSY);
        if (!properties.enabled() || !generator.available())
            throw new BusinessException(WorldSummaryErrorCode.UNAVAILABLE);
        // 접수 전 전체 입력을 검사해 대용량·손상된 출처 요청은 AI 호출 없이 거절한다.
        queries.snapshot(user, world, settings.capture(), properties.maxInputCharacters());
        if (s == null) s = WorldSummary.create(user, world);
        sources.deleteLane(world, true);
        s.queue(w.getCompositionRevision());
        s = summaries.saveAndFlush(s);
        return response(user, world, w, s);
    }

    public List<WorldSummary> runnable() {
        return summaries.runnable(
                GenerationStatus.PENDING,
                GenerationStatus.PROCESSING,
                authClock.instant(),
                PageRequest.of(0, properties.batchSize()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Step claim(Long user, Long world) {
        lock(user);
        var s = summaries.findByWorldIdAndUserId(world, user).orElse(null);
        if (s == null) return null;
        sanitize(s);
        if (s.getStatus() != GenerationStatus.PENDING) return null;
        if (!properties.enabled() || !generator.available()) {
            s.fail("UNAVAILABLE");
            return null;
        }
        var w = compositions.findByWorldIdAndUserId(world, user).orElse(null);
        if (w == null
                || compositionBusy(w)
                || w.getCompositionRevision() != s.getRequestedRevision()) {
            s.fail("INPUT_CHANGED");
            return null;
        }
        try {
            var snapshot =
                    queries.snapshot(
                            user, world, settings.capture(), properties.maxInputCharacters());
            if (snapshot.sources().isEmpty()) {
                s.fail("SOURCE_DELETED");
                return null;
            }
            sources.deleteLane(world, true);
            s.claim(snapshot.fingerprint(), settings.capture(), authClock.instant());
            for (var ref : snapshot.sources()) sources.save(WorldSummarySource.create(s, ref));
            sources.flush();
            summaries.flush();
            return new Step(user, world, s.getAttemptId(), snapshot.data());
        } catch (BusinessException ex) {
            s.fail(
                    ex.getErrorCode() == WorldSummaryErrorCode.TOO_LARGE
                            ? "INPUT_TOO_LARGE"
                            : "INPUT_UNAVAILABLE");
            return null;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void complete(Step step, String output, Throwable failure) {
        lock(step.userId());
        var s = summaries.findByWorldIdAndUserId(step.worldId(), step.userId()).orElse(null);
        if (s == null || !s.accepts(step.attemptId())) return;
        var w = compositions.findByWorldIdAndUserId(step.worldId(), step.userId()).orElse(null);
        if (!s.active(authClock.instant())) {
            fail(s, "ATTEMPT_EXPIRED");
            return;
        }
        if (w == null
                || compositionBusy(w)
                || w.getCompositionRevision() != s.getRequestedRevision()
                || !Objects.equals(s.getAttemptSettings(), settings.capture())
                || !queries.candidateValid(step.userId(), step.worldId())) {
            fail(s, "INPUT_CHANGED");
            return;
        }
        if (failure != null) {
            fail(s, "GENERATION_FAILED");
            return;
        }
        String title;
        try {
            title = validator.title(output);
        } catch (BusinessException ex) {
            fail(s, "INVALID_OUTPUT");
            return;
        }
        var content =
                new WorldSummaryContent(
                        title,
                        step.data().commonKeywords(),
                        step.data().recurringEmotions(),
                        step.data().stories().size(),
                        step.data().limitedEvidence());
        sources.deleteLane(step.worldId(), false);
        sources.publish(step.worldId());
        s.complete(json.writeValueAsString(content), authClock.instant());
        summaries.flush();
    }

    private void fail(WorldSummary s, String code) {
        s.fail(code);
        sources.deleteLane(s.getWorldId(), true);
    }

    private void sanitize(WorldSummary s) {
        if (s == null) return;
        if (s.getResultJson() != null
                && queries.resultSourcesMissing(s.getUserId(), s.getWorldId())) {
            s.discardResult();
            sources.deleteLane(s.getWorldId(), false);
        }
        if (s.getStatus() == GenerationStatus.PROCESSING && !s.active(authClock.instant()))
            fail(s, "ATTEMPT_EXPIRED");
    }

    private boolean compositionBusy(WorldComposition w) {
        return (w.getStatus() == GenerationStatus.PENDING
                        || w.getStatus() == GenerationStatus.PROCESSING)
                && w.getLeaseUntil() != null
                && w.getLeaseUntil().isAfter(authClock.instant());
    }

    private WorldSummaryResponse response(
            Long user, Long world, WorldComposition w, WorldSummary s) {
        long revision = w == null ? 0 : w.getCompositionRevision();
        var stats = queries.stats(user, world);
        var result =
                s == null || s.getResultJson() == null
                        ? null
                        : json.readValue(s.getResultJson(), WorldSummaryContent.class);
        boolean empty = w != null && w.isHasResult() && stats.count() == 0;
        var status =
                empty
                        ? WorldSummaryResponse.Status.EMPTY
                        : s == null
                                ? WorldSummaryResponse.Status.NOT_GENERATED
                                : WorldSummaryResponse.Status.valueOf(s.getStatus().name());
        boolean refresh =
                !empty
                        && w != null
                        && w.isHasResult()
                        && (result == null
                                || !s.matches(revision, settings.capture())
                                || stats.sourceChanged()
                                || stats.count() != stats.validCount());
        return new WorldSummaryResponse(
                world,
                revision,
                s == null ? null : s.getResultRevision(),
                s == null ? 0 : s.getVersion(),
                status,
                s == null ? null : s.getFailureCode(),
                result == null ? null : result.generatedTitle(),
                result == null ? List.of() : result.commonKeywords(),
                result == null ? List.of() : result.recurringEmotions(),
                result == null ? 0 : result.sourceCount(),
                result == null || result.limitedEvidence(),
                stats.sourceChanged(),
                refresh,
                result != null && (status != WorldSummaryResponse.Status.COMPLETED || refresh),
                s == null ? null : s.getGeneratedAt(),
                s == null ? null : s.getPromptVersion());
    }

    private void folder(Long user, Long world) {
        folders.findByIdAndUserId(world, user)
                .orElseThrow(() -> new BusinessException(WorldFolderErrorCode.NOT_FOUND));
    }

    private void lock(Long user) {
        var u =
                users.findByIdForUpdate(user)
                        .orElseThrow(() -> new BusinessException(WorldFolderErrorCode.NOT_FOUND));
        if (!u.isOnboardingCompleted())
            throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }
}
