package com.mongle.backend.domain.dream.service;

import com.mongle.backend.domain.dream.analysis.DreamAnalysis;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.generation.DreamGenerationGuard;
import com.mongle.backend.domain.dream.generation.DreamGenerationJob;
import com.mongle.backend.domain.dream.generation.DreamGenerationJobRepository;
import com.mongle.backend.domain.dream.generation.DreamGenerationTransactions;
import com.mongle.backend.domain.dream.repository.DreamAnalysisRepository;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.dream.story.DreamStoryRepository;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.world.bridge.BridgeResultRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DreamService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final DreamRepository dreams;
    private final UserRepository users;
    private final DreamAnalysisRepository analyses;
    private final DreamStoryRepository stories;
    private final Clock authClock;
    private final DreamGenerationJobRepository generationJobs;
    private final DreamGenerationGuard generationGuard;
    private final DreamGenerationTransactions generationTransactions;
    private final BridgeResultRepository bridges;

    @Transactional
    public DreamResponse create(Long userId, DreamCreateRequest request) {
        var user = lockUser(userId);
        validateDate(request.dreamedAt());
        if (dreams.existsByUserIdAndDreamedAt(userId, request.dreamedAt())) {
            throw new BusinessException(DreamErrorCode.DATE_OCCUPIED);
        }
        return response(
                dreams.save(Dream.create(user, request.originalText(), request.dreamedAt())));
    }

    @Transactional
    public DreamResponse saveDraft(Long userId, LocalDate date, DreamDraftRequest request) {
        // 사용자 행을 먼저 잠가 최초 자동저장·직접 등록·삭제의 경쟁을 같은 순서로 처리한다.
        var user = lockUser(userId);
        validateDate(date);
        var existing = dreams.findByUserIdAndDreamedAt(userId, date);
        if (existing.isEmpty()) {
            // 삭제된 초안의 지연 저장 요청이 새 기록을 만드는 것을 막는다.
            if (request.revision() != null || request.dreamId() != null)
                throw new BusinessException(DreamErrorCode.VERSION_CONFLICT);
            return response(dreams.save(Dream.draft(user, request.originalText(), date)));
        }
        var dream = existing.get();
        if (!dream.getId().equals(request.dreamId()))
            throw new BusinessException(DreamErrorCode.VERSION_CONFLICT);
        dream.checkRevision(request.revision());
        dream.saveDraft(request.originalText());
        return response(dream);
    }

    @Transactional
    public DreamResponse submit(Long userId, Long id, Long revision) {
        lockUser(userId);
        var dream = owned(userId, id);
        dream.checkRevision(revision);
        dream.submit();
        return response(dream);
    }

    @Transactional
    public DreamResponse complete(Long userId, Long id, DreamEmotionsRequest request) {
        lockUser(userId);
        var dream = owned(userId, id);
        dream.checkRevision(request.revision());
        dream.complete(request.emotions());
        // 꿈 저장과 예약을 함께 커밋한다. 워커는 커밋된 작업만 조회한다.
        generationJobs.save(DreamGenerationJob.create(dream, authClock.instant()));
        return response(dream);
    }

    public DreamResponse get(Long userId, Long id) {
        return readResponse(owned(userId, id));
    }

    public record IncompleteDreams(List<DreamResponse> items, boolean hasNext) {}

    public IncompleteDreams incomplete(Long userId, int page, int size) {
        var result =
                dreams.findByUserIdAndRecordStatusNotOrderByDreamedAtDescIdDesc(
                        userId, DreamRecordStatus.COMPLETED, PageRequest.of(page, size));
        return new IncompleteDreams(
                result.getContent().stream().map(DreamResponse::from).toList(), result.hasNext());
    }

    @Transactional
    public DreamResponse update(Long userId, Long id, DreamUpdateRequest request) {
        lockUser(userId);
        var dream = owned(userId, id);
        dream.checkRevision(request.getRevision());
        generationGuard.requireMutable(userId, id);
        if (!request.isTextProvided()
                && !request.isEmotionsProvided()
                && !request.isTitleProvided()) {
            throw new BusinessException(DreamErrorCode.EMPTY_UPDATE);
        }
        dream.update(
                request.isTextProvided(),
                request.getOriginalText(),
                request.isEmotionsProvided(),
                request.getEmotions(),
                request.isTitleProvided(),
                request.getTitle());
        return response(dream);
    }

    @Transactional
    public void delete(Long userId, Long id, Long revision) {
        lockUser(userId);
        var dream = owned(userId, id);
        dream.checkRevision(revision);
        generationGuard.requireMutable(userId, id);

        // 연결 결과를 먼저 제거한다. 같은 사용자 잠금으로 늦은 완료 저장과 직렬화한다.
        // 새로 인접한 꿈 사이의 연결 문장은 생성하지 않는다.
        bridges.deleteForDream(userId, id);

        // 분석 FK를 해제하기 전에 생성 중 이야기를 실패 처리한다. 완료 결과는 보존한다.
        stories.failProcessingForDeletedDream(
                id, GenerationStatus.PROCESSING, GenerationStatus.FAILED);
        analyses.detachFromDream(id);

        generationJobs.deleteByDreamIdAndUserId(id, userId);

        // 소프트 삭제나 원문 스냅샷을 남기지 않는다. 감정 컬렉션도 함께 삭제된다.
        dreams.delete(dream);
        dreams.flush();
    }

    private DreamResponse response(Dream dream) {
        dreams.flush();
        return readResponse(dream);
    }

    private DreamResponse readResponse(Dream dream) {
        if (dream.getId() == null || dream.getRecordStatus() != DreamRecordStatus.COMPLETED) {
            return DreamResponse.from(dream);
        }

        var analysis = analyses.findDisplayMetadata(dream.getId(), dream.getUser().getId());
        var result =
                analysis.filter(DreamAnalysis::hasResult)
                        .map(
                                a ->
                                        DreamResponse.from(
                                                dream,
                                                a.getDisplayKeywords(),
                                                dream.getSourceRevision() != a.visibleRevision()))
                        .orElseGet(() -> DreamResponse.from(dream));
        var job = generationJobs.findByDreamIdAndUserId(dream.getId(), dream.getUser().getId());
        return result.withGeneration(
                analysis.map(a -> a.getResultRevision()).orElse(null),
                job.isEmpty()
                        ? null
                        : generationTransactions.get(dream.getUser().getId(), dream.getId()));
    }

    private Dream owned(Long userId, Long id) {
        // 타인의 ID와 없는 ID는 같은 응답으로 처리한다.
        return dreams.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
    }

    private User lockUser(Long userId) {
        var user =
                users.findByIdForUpdate(userId)
                        .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
        if (!user.isOnboardingCompleted())
            throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
        return user;
    }

    private void validateDate(LocalDate date) {
        if (date == null) throw new BusinessException(DreamErrorCode.DATE_REQUIRED);
        if (date.isAfter(LocalDate.now(authClock.withZone(KOREA)))) {
            throw new BusinessException(DreamErrorCode.FUTURE_DATE);
        }
    }
}
