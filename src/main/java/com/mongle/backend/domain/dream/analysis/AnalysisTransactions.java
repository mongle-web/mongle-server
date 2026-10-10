package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AnalysisTransactions {
    private final UserRepository users;
    private final DreamRepository dreams;
    private final StoredAnalysisRepository analyses;
    private final EntityManager em;
    private final Clock authClock;
    private final AnalysisResultCodec codec;

    public record Reservation(AnalysisResponse response, StructureGenerator.Input input) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation begin(Long userId, Long dreamId, Long revision, boolean available) {
        return reserve(userId, dreamId, revision, null, available);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation beginForSource(
            Long userId, Long dreamId, long sourceRevision, boolean available) {
        return reserve(userId, dreamId, null, sourceRevision, available);
    }

    private Reservation reserve(
            Long userId, Long dreamId, Long revision, Long expectedSource, boolean available) {
        lock(userId);

        var dream =
                dreams.findByIdAndUserId(dreamId, userId)
                        .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
        if (expectedSource != null && dream.getSourceRevision() != expectedSource) {
            throw new BusinessException(DreamErrorCode.VERSION_CONFLICT);
        }
        var prior = analyses.findBySourceDreamIdAndUserId(dreamId, userId);
        var now = authClock.instant();

        if (prior.isPresent()
                && (prior.get().getStatus() == GenerationStatus.COMPLETED
                        || prior.get().active(now))) {
            return new Reservation(response(prior.get()), null);
        }

        if (dream.getRecordStatus() != DreamRecordStatus.COMPLETED) {
            throw new BusinessException(DreamErrorCode.INVALID_STATE);
        }

        if (expectedSource == null) {
            dream.checkRevision(revision);
        }

        if (!available) {
            throw new BusinessException(AnalysisErrorCode.UNAVAILABLE);
        }

        var analysis = prior.orElseGet(() -> DreamAnalysis.create(dream));
        analysis.start(dream.getSourceRevision(), now, Duration.ofMinutes(2));
        dream.changeAnalysisStatus(GenerationStatus.PROCESSING);
        analyses.save(analysis);
        em.flush();

        var input =
                new StructureGenerator.Input(
                        userId,
                        analysis.getId(),
                        analysis.getAttemptId(),
                        dream.getOriginalText(),
                        dream.getEmotions());

        return new Reservation(response(analysis), input);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AnalysisResponse finish(StructureGenerator.Input input, StructureResult result) {
        lock(input.userId());

        var analysis = owned(input.userId(), input.analysisId());

        if (!analysis.accepts(input.attemptId())) {
            return response(analysis);
        }

        var dream = analysis.getDream();

        if (dream == null) {
            analysis.fail("SOURCE_DELETED");
            return response(analysis);
        }

        if (dream.getSourceRevision() != analysis.getObservedRevision()) {
            analysis.fail("SOURCE_CHANGED");
            dream.changeAnalysisStatus(GenerationStatus.FAILED);
            em.flush();
            return response(analysis);
        }

        if (!analysis.active(authClock.instant())) {
            analysis.fail("ATTEMPT_EXPIRED");
            return response(analysis);
        }

        if (analysis.isRegenerating()) {
            // 새 분석은 서사 입력에만 사용한다. 조회·통계는 아직 기존 성공 결과를 사용한다.
            analysis.stage(codec.encode(result));
            em.flush();
            return response(analysis);
        }

        publish(analysis, result);
        return response(analysis);
    }

    /** StoryTransactions의 성공 트랜잭션에 참여한다. 외부 호출은 하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishPending(DreamAnalysis analysis) {
        if (analysis.getPendingResultJson() != null) {
            publish(analysis, codec.decode(analysis.getPendingResultJson()));
        }
    }

    public StructureResult generationContext(DreamAnalysis analysis) {
        if (analysis.getPendingResultJson() != null) {
            return codec.decode(analysis.getPendingResultJson());
        }
        var current = response(analysis);
        return new StructureResult(current.generatedTitle(), current.displayKeywords(),
                current.elements(), current.scenes());
    }

    private void publish(DreamAnalysis analysis, StructureResult result) {
        var dream = analysis.getDream();
        // 장면-요소 FK를 먼저 정리한다. 새 성공 결과만 현재 조회·통계에 한 번 집계한다.
        em.createQuery("delete from DreamSceneEntity l where l.dreamScene.analysis.id=:id")
                .setParameter("id", analysis.getId()).executeUpdate();
        em.createQuery("delete from DreamScene s where s.analysis.id=:id")
                .setParameter("id", analysis.getId()).executeUpdate();
        em.createQuery("delete from DreamEntity e where e.analysis.id=:id")
                .setParameter("id", analysis.getId()).executeUpdate();

        Map<String, DreamEntity> elements = new HashMap<>();

        for (var e : result.elements()) {
            var entity = DreamEntity.create(analysis, e.type(), e.name(), e.description());
            em.persist(entity);
            elements.put(e.key(), entity);
        }

        for (var s : result.scenes()) {
            var scene =
                    DreamScene.create(
                            analysis, s.sequence(), s.content(), s.disconnectedFromPrevious());
            em.persist(scene);

            for (var key : s.elementKeys()) {
                em.persist(DreamSceneEntity.link(scene, elements.get(key)));
            }
        }

        dream.applyGeneratedTitle(result.generatedTitle(), analysis.getSourceRevision());
        dream.changeAnalysisStatus(GenerationStatus.COMPLETED);
        analysis.finish(dream.getSourceRevision(), result);
        // 자동 제목 저장은 revision을 바꿀 수 있지만 AI 입력 sourceRevision은 유지한다.
        em.flush();

    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AnalysisResponse fail(StructureGenerator.Input input, String code) {
        lock(input.userId());

        var analysis = owned(input.userId(), input.analysisId());

        if (!analysis.accepts(input.attemptId())) {
            return response(analysis);
        }

        analysis.fail(code);

        if (analysis.getDream() != null) {
            analysis.getDream().changeAnalysisStatus(GenerationStatus.FAILED);
        }

        em.flush();

        return response(analysis);
    }

    @Transactional(readOnly = true)
    public AnalysisResponse get(Long userId, Long analysisId) {
        return response(owned(userId, analysisId));
    }

    private DreamAnalysis owned(Long userId, Long id) {
        return analyses.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(AnalysisErrorCode.NOT_FOUND));
    }

    private void lock(Long id) {
        var u =
                users.findByIdForUpdate(id)
                        .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
        if (!u.isOnboardingCompleted())
            throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }

    public AnalysisResponse response(DreamAnalysis a) {
        var sceneRows =
                em.createQuery(
                                "select s from DreamScene s where s.analysis.id=:id order by"
                                        + " s.sequenceNo",
                                DreamScene.class)
                        .setParameter("id", a.getId())
                        .getResultList();
        var entityRows =
                em.createQuery(
                                "select e from DreamEntity e where e.analysis.id=:id order by e.id",
                                DreamEntity.class)
                        .setParameter("id", a.getId())
                        .getResultList();
        var links =
                em.createQuery(
                                "select l from DreamSceneEntity l join fetch l.dreamScene join"
                                        + " fetch l.dreamEntity where l.dreamScene.analysis.id=:id",
                                DreamSceneEntity.class)
                        .setParameter("id", a.getId())
                        .getResultList();
        var refs = new HashMap<Long, List<String>>();
        for (var l : links)
            refs.computeIfAbsent(l.getDreamScene().getId(), k -> new ArrayList<>())
                    .add("e" + l.getDreamEntity().getId());
        var scenes =
                sceneRows.stream()
                        .map(
                                s ->
                                        new StructureResult.Scene(
                                                s.getSequenceNo(),
                                                s.getContent(),
                                                s.isDisconnectedFromPrevious(),
                                                refs.getOrDefault(s.getId(), List.of()).stream()
                                                        .sorted()
                                                        .toList()))
                        .toList();
        var elements =
                entityRows.stream()
                        .map(
                                e ->
                                        new StructureResult.Element(
                                                "e" + e.getId(),
                                                e.getEntityType(),
                                                e.getName(),
                                                e.getDescription()))
                        .toList();
        return new AnalysisResponse(
                a.getId(),
                a.getDream() == null ? null : a.getDream().getId(),
                a.getDreamedAt(),
                a.getSourceRevision(),
                a.getStatus(),
                a.getFailureCode(),
                a.getDream() == null,
                a.getDream() != null && a.getDream().getSourceRevision() != a.visibleRevision(),
                a.getDream() == null ? null : a.getDream().getRevision(),
                a.getGeneratedTitle(),
                a.getDisplayKeywords(),
                scenes,
                elements,
                a.getResultRevision());
    }
}
