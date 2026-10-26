package com.mongle.backend.domain.world.composition;

import com.mongle.backend.domain.dream.gateway.DreamGenerationSettings;
import com.mongle.backend.domain.dream.generation.DreamGenerationGuard;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.world.bridge.*;
import com.mongle.backend.domain.world.folder.*;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Clock;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldCompositionTransactions {
    private final UserRepository users;
    private final WorldFolderRepository folders;
    private final WorldCompositionRepository worlds;
    private final WorldCompositionEntryRepository entries;
    private final WorldCompositionQueries queries;
    private final DreamGenerationGuard guard;
    private final DreamGenerationSettings settings;
    private final WorldCompositionProperties properties;
    private final Clock authClock;

    public record Step(
            Long userId,
            Long worldId,
            String attempt,
            String claim,
            Long entryId,
            Long before,
            Long after,
            Long bridgeId,
            boolean retrying) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public WorldCompositionResponse build(Long user, Long world, WorldBuildRequest request) {
        lock(user);
        var folder = folder(user, world);
        folder.checkRevision(request.folderRevision());
        var w =
                worlds.findByWorldIdAndUserId(world, user)
                        .orElseGet(() -> WorldComposition.create(world, user));
        if (w.active(authClock.instant())) {
            if ("BUILD".equals(w.getRequestType())
                    && w.getBaseRevision() == request.compositionRevision()
                    && w.getFolderRevision() == request.folderRevision())
                return response(w, folder);
            throw new BusinessException(WorldCompositionErrorCode.BUSY);
        }
        checkRevision(w, request.compositionRevision());
        requireEnabled();
        var sources = queries.sources(user, world);
        if (sources.size() < 2)
            throw new BusinessException(WorldCompositionErrorCode.SOURCES_REQUIRED);
        if (sources.size() != folder.getSelectedDreamCount())
            throw new BusinessException(WorldCompositionErrorCode.SOURCE_NOT_READY);
        for (var source : sources) {
            if (source.versionId() == null)
                throw new BusinessException(WorldCompositionErrorCode.SOURCE_NOT_READY);
            guard.requireNarrativeAvailable(user, source.dreamId());
        }
        entries.deleteLane(world, true);
        w.start(
                folder.getRevision(),
                "BUILD",
                null,
                null,
                settings.capture(),
                BridgePrompt.VERSION,
                authClock.instant());
        worlds.saveAndFlush(w);
        long position = 0;
        for (var source : sources)
            entries.save(
                    WorldCompositionEntry.create(
                            world, user, position++, source.dreamId(), source.versionId()));
        entries.flush();
        return response(w, folder);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public WorldCompositionResponse apply(
            Long user, Long world, Long dream, WorldApplyRequest request) {
        lock(user);
        var folder = folder(user, world);
        folder.checkRevision(request.folderRevision());
        var w = owned(user, world);
        if (w.active(authClock.instant())) {
            if ("APPLY".equals(w.getRequestType())
                    && w.getBaseRevision() == request.compositionRevision()
                    && w.getFolderRevision() == request.folderRevision()
                    && Objects.equals(w.getTargetDreamId(), dream)
                    && Objects.equals(w.getTargetVersionId(), request.storyVersionId()))
                return response(w, folder);
            throw new BusinessException(WorldCompositionErrorCode.BUSY);
        }
        checkRevision(w, request.compositionRevision());
        if (!w.isHasResult()) throw new BusinessException(WorldCompositionErrorCode.NOT_FOUND);
        var current = entries.findByWorldIdAndCandidateOrderByPositionAsc(world, false);
        int target = -1;
        for (int index = 0; index < current.size(); index++)
            if (current.get(index).getDreamId().equals(dream)) target = index;
        if (target < 0) throw new BusinessException(WorldCompositionErrorCode.NOT_FOUND);
        if (Objects.equals(current.get(target).getStoryVersionId(), request.storyVersionId()))
            return response(w, folder);
        if (!Objects.equals(queries.currentVersion(user, dream), request.storyVersionId()))
            throw new BusinessException(WorldCompositionErrorCode.SOURCE_NOT_READY);
        requireEnabled();
        guard.requireNarrativeAvailable(user, dream);
        entries.deleteLane(world, true);
        var candidates = current.stream().map(WorldCompositionEntry::copy).toList();
        candidates.get(target).replace(request.storyVersionId(), target > 0);
        if (target + 1 < candidates.size()) candidates.get(target + 1).reconnect();
        w.start(
                folder.getRevision(),
                "APPLY",
                dream,
                request.storyVersionId(),
                settings.capture(),
                BridgePrompt.VERSION,
                authClock.instant());
        worlds.flush();
        entries.saveAll(candidates);
        entries.flush();
        return response(w, folder);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public WorldCompositionResponse retry(Long user, Long world, Long version) {
        lock(user);
        var folder = folder(user, world);
        var w = owned(user, world);
        if (w.getVersion() != version)
            throw new BusinessException(WorldCompositionErrorCode.CONFLICT);
        if (w.active(authClock.instant())) return response(w, folder);
        if (w.working()) w.fail("ATTEMPT_EXPIRED");
        if (w.getStatus() != GenerationStatus.FAILED)
            throw new BusinessException(WorldCompositionErrorCode.RETRY_REQUIRED);
        requireEnabled();
        validate(w, folder);
        for (var e : entries.findByWorldIdAndCandidateOrderByPositionAsc(world, true)) {
            if ("BUILD".equals(w.getRequestType())
                    || Objects.equals(e.getDreamId(), w.getTargetDreamId()))
                guard.requireNarrativeAvailable(user, e.getDreamId());
        }
        w.retry(authClock.instant());
        worlds.flush();
        return response(w, folder);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public WorldCompositionResponse get(Long user, Long world) {
        var folder = folder(user, world);
        return worlds.findByWorldIdAndUserId(world, user)
                .map(w -> response(w, folder))
                .orElse(
                        new WorldCompositionResponse(
                                world,
                                folder.getRevision(),
                                0,
                                false,
                                GenerationStatus.PENDING,
                                null,
                                null,
                                0,
                                folder.getSelectedDreamCount() > 0,
                                false,
                                false,
                                false));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public WorldEntryPage page(Long user, Long world, String cursor, int size) {
        folder(user, world);
        var w =
                worlds.findByWorldIdAndUserId(world, user)
                        .orElseGet(() -> WorldComposition.create(world, user));
        return queries.page(user, w, cursor, size);
    }

    public List<WorldCompositionRepository.Candidate> runnable() {
        return worlds.runnable(
                List.of(GenerationStatus.PENDING, GenerationStatus.PROCESSING),
                authClock.instant(),
                PageRequest.of(0, properties.batchSize()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Step next(Long user, Long world) {
        lock(user);
        var found = worlds.findByWorldIdAndUserId(world, user);
        if (found.isEmpty()) return null;
        var w = found.get();
        if (!w.working()) return null;
        var now = authClock.instant();
        if (!w.active(now)) {
            w.fail("ATTEMPT_EXPIRED");
            return null;
        }
        if (w.getClaimToken() != null) {
            if (w.getClaimUntil().isAfter(now)) return null;
            w.fail("WORKER_INTERRUPTED");
            return null;
        }
        var folder = folder(user, world);
        try {
            validateMetadata(w, folder);
        } catch (BusinessException ex) {
            w.fail("INPUT_CHANGED");
            return null;
        }
        var pending = entries.pending(world, PageRequest.of(0, 1));
        if (pending.isEmpty()) {
            if (!queries.sourcesValid(w)) {
                w.fail("INPUT_CHANGED");
                return null;
            }
            entries.deleteLane(world, false);
            entries.publish(world);
            w.complete();
            worlds.flush();
            return null;
        }
        var after = pending.getFirst();
        var previous = entries.previous(world, after.getPosition(), PageRequest.of(0, 1));
        if (previous.isEmpty()) {
            w.fail("INVALID_COMPOSITION");
            return null;
        }
        w.claim(now);
        worlds.flush();
        return new Step(
                user,
                world,
                w.getAttemptId(),
                w.getClaimToken(),
                after.getId(),
                previous.getFirst().getStoryVersionId(),
                after.getStoryVersionId(),
                after.getBridgeId(),
                w.isRetrying());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void completed(Step step, BridgeResponse bridge, boolean failed) {
        lock(step.userId());
        var found = worlds.findByWorldIdAndUserId(step.worldId(), step.userId());
        if (found.isEmpty()) return;
        var w = found.get();
        if (!Objects.equals(w.getAttemptId(), step.attempt())
                || !Objects.equals(w.getClaimToken(), step.claim())) return;
        if (!w.accepts(step.attempt(), step.claim(), authClock.instant())) {
            w.fail("ATTEMPT_EXPIRED");
            return;
        }
        if (failed || bridge == null) {
            w.fail("BRIDGE_FAILED");
            return;
        }
        if (!Objects.equals(bridge.beforeStoryVersionId(), step.before())
                || !Objects.equals(bridge.afterStoryVersionId(), step.after())) {
            w.fail("INVALID_BRIDGE");
            return;
        }
        if (bridge.status() == GenerationStatus.FAILED) {
            w.fail("BRIDGE_FAILED");
            return;
        }
        var entry = entries.findById(step.entryId()).orElse(null);
        if (entry == null || !entry.isCandidate() || !entry.getWorldId().equals(step.worldId())) {
            w.fail("INPUT_CHANGED");
            return;
        }
        if (bridge.status() == GenerationStatus.COMPLETED) entry.resolved(bridge.bridgeId());
        else entry.waiting(bridge.bridgeId());
        w.advance(authClock.instant());
        worlds.flush();
    }

    private void validate(WorldComposition w, WorldFolder folder) {
        validateMetadata(w, folder);
        if (!queries.sourcesValid(w))
            throw new BusinessException(WorldCompositionErrorCode.CONFLICT);
    }

    private void validateMetadata(WorldComposition w, WorldFolder folder) {
        if (w.getFolderRevision() != folder.getRevision()
                || w.getBaseRevision() != w.getCompositionRevision()
                || !Objects.equals(w.getSettingsJson(), settings.capture())
                || !Objects.equals(w.getPromptVersion(), BridgePrompt.VERSION))
            throw new BusinessException(WorldCompositionErrorCode.CONFLICT);
    }

    private void checkRevision(WorldComposition w, Long revision) {
        if (revision == null || revision != w.getCompositionRevision())
            throw new BusinessException(WorldCompositionErrorCode.CONFLICT);
    }

    private void requireEnabled() {
        if (!properties.enabled())
            throw new BusinessException(WorldCompositionErrorCode.UNAVAILABLE);
    }

    private WorldComposition owned(Long user, Long world) {
        return worlds.findByWorldIdAndUserId(world, user)
                .orElseThrow(() -> new BusinessException(WorldCompositionErrorCode.NOT_FOUND));
    }

    private WorldFolder folder(Long user, Long world) {
        return folders.findByIdAndUserId(world, user)
                .orElseThrow(() -> new BusinessException(WorldFolderErrorCode.NOT_FOUND));
    }

    private void lock(Long user) {
        var u =
                users.findByIdForUpdate(user)
                        .orElseThrow(
                                () -> new BusinessException(WorldCompositionErrorCode.NOT_FOUND));
        if (!u.isOnboardingCompleted())
            throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }

    private WorldCompositionResponse response(WorldComposition w, WorldFolder folder) {
        boolean expired = w.working() && !w.active(authClock.instant());
        var changes = queries.changes(w.getWorldId());
        return new WorldCompositionResponse(
                w.getWorldId(),
                folder.getRevision(),
                w.getCompositionRevision(),
                w.isHasResult(),
                expired ? GenerationStatus.FAILED : w.getStatus(),
                expired ? "ATTEMPT_EXPIRED" : w.getFailureCode(),
                w.getAttemptId(),
                w.getVersion(),
                queries.selectionChanged(w.getUserId(), w.getWorldId()),
                changes.sourceChanged(),
                changes.newStoryAvailable(),
                changes.hasConnectionGaps());
    }
}
