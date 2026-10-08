package com.mongle.backend.domain.archive.service;

import com.mongle.backend.domain.archive.dto.request.ArchiveSearch;
import com.mongle.backend.domain.archive.dto.response.*;
import com.mongle.backend.domain.archive.error.ArchiveErrorCode;
import com.mongle.backend.domain.archive.repository.ArchiveQueryRepository;
import com.mongle.backend.domain.dream.analysis.DreamAnalysis;
import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.domain.dream.image.DreamImage;
import com.mongle.backend.domain.dream.story.DreamStory;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 화면에 필요한 정보를 읽기 전용 트랜잭션에서 조립한다. AI 호출·URL 발급·생성 작업은 수행하지 않는다.
 * DB 엔티티를 반환하지 않아 트랜잭션 종료 후 프론트 응답 직렬화가 추가 쿼리를 일으키지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArchiveService {
    private final ArchiveQueryRepository queries;

    /** 크기가 제한된 페이지를 조회하고 실제 반환하는 마지막 카드로 다음 커서를 발급한다. */
    public ArchivePage list(Long userId, @Nullable String month, @Nullable String date,
                            @Nullable String cursor, int size) {
        var search = ArchiveSearch.parse(month, date, cursor, size);
        var rows = queries.findDreams(userId, search);
        boolean hasNext = rows.size() > size;
        var selected = hasNext ? rows.subList(0, size) : rows;
        var items = items(userId, selected);
        String next = null;
        if (hasNext) {
            var last = selected.getLast();
            next = search.cursorAfter(last.getDreamedAt(), last.getId());
        }
        return new ArchivePage(items, hasNext, next);
    }

    /** 초안·삭제·타인 기록은 동일한 404다. 분석·이미지가 없어도 완성한 원문 기록은 조회한다. */
    public ArchiveDetail detail(Long userId, Long dreamId) {
        var dream = queries.findDream(userId, dreamId)
                .orElseThrow(() -> new BusinessException(ArchiveErrorCode.NOT_FOUND));
        return new ArchiveDetail(items(userId, List.of(dream)).getFirst(), dream.getOriginalText(),
                dream.getEmotions().stream().sorted().toList(), dream.getCreatedAt(), dream.getUpdatedAt());
    }

    private List<ArchiveItem> items(Long userId, List<Dream> dreams) {
        if (dreams.isEmpty()) return List.of();
        var analyses = queries.findAnalyses(userId, dreams.stream().map(Dream::getId).toList());
        var byDream = analyses.stream().collect(Collectors.toMap(DreamAnalysis::getSourceDreamId, Function.identity()));
        var ids = analyses.stream().map(DreamAnalysis::getId).toList();
        // IN ()를 실행하지 않는다. 관련 정보가 있으면 행 수와 무관하게 쿼리 두 번으로 한꺼번에 읽는다.
        Map<Long, DreamStory> stories = ids.isEmpty() ? Map.of() : queries.findStories(userId, ids).stream()
                .collect(Collectors.toMap(s -> s.getAnalysis().getId(), Function.identity()));
        Map<Long, DreamImage> images = ids.isEmpty() ? Map.of() : queries.findImages(userId, ids).stream()
                .collect(Collectors.toMap(i -> i.getAnalysis().getId(), Function.identity()));
        return dreams.stream().map(dream -> {
            var analysis = byDream.get(dream.getId());
            var story = analysis == null ? null : stories.get(analysis.getId());
            var image = analysis == null ? null : images.get(analysis.getId());
            var keywords = analysis != null && analysis.getStatus() == GenerationStatus.COMPLETED
                    ? analysis.getDisplayKeywords() : List.<String>of();
            return new ArchiveItem(dream.getId(), dream.getDreamedAt(), dream.getTitle(), keywords,
                    dream.isEdited(), dream.getRevision(), dream.getSourceRevision(),
                    analysis == null ? null : new ArchiveItem.Analysis(analysis.getId(), analysis.getStatus(),
                            analysis.getFailureCode(), analysis.getObservedRevision() != dream.getSourceRevision()),
                    story(story, dream), image(image, story, dream));
        }).toList();
    }

    private ArchiveItem.@Nullable Story story(@Nullable DreamStory story, Dream dream) {
        if (story == null) return null;
        boolean hasResult = story.getResultJson() != null;
        long visibleRevision = story.getResultRevision() == null ? story.getSourceRevision() : story.getResultRevision();
        return new ArchiveItem.Story(story.getId(), story.getVersion(), story.getStatus(), story.getFailureCode(),
                hasResult, hasResult && story.getStatus() != GenerationStatus.COMPLETED,
                story.getResultRevision(), visibleRevision != dream.getSourceRevision());
    }

    private ArchiveItem.@Nullable Image image(@Nullable DreamImage image, @Nullable DreamStory story, Dream dream) {
        if (image == null) return null;
        boolean hasResult = image.getStorageKey() != null;
        long visibleRevision = image.getResultRevision() == null ? image.getSourceRevision() : image.getResultRevision();
        String visibleHash = image.getResultStoryHash() == null ? image.getStoryHash() : image.getResultStoryHash();
        // 이야기의 시도 버전만 비교하면 실패 재생성도 이미지 변경으로 오인한다. 실제 표시 결과의 해시를 비교한다.
        boolean storyChanged = story != null && story.getResultJson() != null
                && !Objects.equals(visibleHash, hash(story.getResultJson()));
        return new ArchiveItem.Image(image.getId(), image.getVersion(), image.getStatus(), image.getFailureCode(),
                hasResult, hasResult && image.getStatus() != GenerationStatus.COMPLETED, image.getResultRevision(),
                visibleRevision != dream.getSourceRevision(), storyChanged,
                image.getContentType(), image.getWidth(), image.getHeight());
    }

    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("이미지 출처 비교 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
