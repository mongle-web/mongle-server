package com.mongle.backend.domain.dream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.dream.dto.DreamCreateRequest;
import com.mongle.backend.domain.dream.generation.DreamGenerationJobRepository;
import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.repository.*;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.DreamStoryRepository;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class DreamPolicyTest {
    @Test
    void rejectsUnicodeWhitespaceTitlesButAllowsClearingAndRealText() {
        for (String title :
                List.of("", " \t\n", "\u00a0", "\u2007", "\u202f", " \u00a0\u2007\u202f\t")) {
            assertThatThrownBy(() -> DreamPolicy.title(title))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(DreamErrorCode.INVALID_TITLE));
        }
        assertThatCode(() -> DreamPolicy.title(null)).doesNotThrowAnyException();
        assertThatCode(() -> DreamPolicy.title("\u00a0꿈 제목\u202f")).doesNotThrowAnyException();
        assertThatCode(() -> DreamPolicy.title("🌙".repeat(20))).doesNotThrowAnyException();
        assertThatThrownBy(() -> DreamPolicy.title("🌙".repeat(21)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void allowsBlankAutosaveButRequiresTextBeforeNext() {
        var draft =
                Dream.draft(User.create("test@example.com", "몽글"), "", LocalDate.of(2026, 10, 1));
        assertThatThrownBy(draft::submit).isInstanceOf(BusinessException.class);
        draft.saveDraft("꿈 내용");
        draft.submit();
        assertThat(draft.getRecordStatus()).isEqualTo(DreamRecordStatus.EMOTION_PENDING);
        assertThatThrownBy(() -> draft.complete(List.of(DreamEmotion.HAPPY, DreamEmotion.HAPPY)))
                .isInstanceOf(BusinessException.class);
        draft.complete(List.of(DreamEmotion.CALM, DreamEmotion.HAPPY));
        assertThat(draft.getEmotions())
                .containsExactlyInAnyOrder(DreamEmotion.CALM, DreamEmotion.HAPPY);
    }

    @Test
    void refusesLateGeneratedTitleAfterUserEdit() {
        var dream =
                Dream.create(
                        User.create("test@example.com", "몽글"), "원문", LocalDate.of(2026, 10, 1));
        dream.complete(List.of(DreamEmotion.HAPPY));
        dream.update(false, null, false, null, true, "내 제목");
        dream.applyGeneratedTitle("늦게 도착한 AI 제목", 0);
        assertThat(dream.getTitle()).isEqualTo("내 제목");
        dream.update(false, null, false, null, true, null);
        dream.applyGeneratedTitle("늦게 도착한 AI 제목", 0);
        assertThat(dream.getTitle()).isNull();
        assertThat(dream.isEdited()).isTrue();
    }

    @Test
    void appliesGeneratedTitleOnlyToTheUnchangedSourceRevision() {
        var dream =
                Dream.create(
                        User.create("test@example.com", "몽글"), "원문", LocalDate.of(2026, 10, 1));
        dream.applyGeneratedTitle("다른 버전의 제목", 1);
        assertThat(dream.getTitle()).isNull();
        dream.applyGeneratedTitle("AI 제목", 0);
        assertThat(dream.getTitle()).isEqualTo("AI 제목");
        assertThat(dream.isEdited()).isFalse();
    }

    @Test
    void 한국_자정_이후에는_UTC_날짜가_전날이어도_오늘_등록을_허용한다() {
        var users = mock(UserRepository.class);
        var dreams = mock(DreamRepository.class);
        var user = User.create("test@example.com", "몽글");
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(dreams.save(any(Dream.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var service =
                new DreamService(
                        dreams,
                        users,
                        mock(DreamAnalysisRepository.class),
                        mock(DreamStoryRepository.class),
                        Clock.fixed(Instant.parse("2026-10-01T15:00:00Z"), ZoneOffset.UTC),
                        mock(DreamGenerationJobRepository.class),
                        mock(com.mongle.backend.domain.dream.generation.DreamGenerationGuard.class),
                        mock(com.mongle.backend.domain.dream.generation.DreamGenerationTransactions.class));
        assertThat(
                        service.create(
                                        1L,
                                        new DreamCreateRequest(LocalDate.of(2026, 10, 2), "자정의 꿈"))
                                .dreamedAt())
                .isEqualTo(LocalDate.of(2026, 10, 2));
    }

    @Test
    void 한국_자정_직전에는_다음_날짜를_거절한다() {
        var users = mock(UserRepository.class);
        when(users.findByIdForUpdate(1L))
                .thenReturn(Optional.of(User.create("test@example.com", "몽글")));
        var service =
                new DreamService(
                        mock(DreamRepository.class),
                        users,
                        mock(DreamAnalysisRepository.class),
                        mock(DreamStoryRepository.class),
                        Clock.fixed(Instant.parse("2026-10-01T14:59:59Z"), ZoneOffset.UTC),
                        mock(DreamGenerationJobRepository.class),
                        mock(com.mongle.backend.domain.dream.generation.DreamGenerationGuard.class),
                        mock(com.mongle.backend.domain.dream.generation.DreamGenerationTransactions.class));
        assertThatThrownBy(
                        () ->
                                service.create(
                                        1L,
                                        new DreamCreateRequest(LocalDate.of(2026, 10, 2), "내일 꿈")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DreamErrorCode.FUTURE_DATE));
    }
}
