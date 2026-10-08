package com.mongle.backend.domain.dream;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

class DreamEditPolicyTest {
    private Dream completed() {
        var dream =
                Dream.create(
                        User.create("policy@test.com", "테스터"), "원문", LocalDate.of(2026, 10, 6));
        dream.complete(List.of(DreamEmotion.HAPPY, DreamEmotion.CALM));
        return dream;
    }

    @Test
    void titleCountsCodePointsAndClearsUnicodeWhitespace() {
        var dream = completed();
        long source = dream.getSourceRevision();
        for (String clear : new String[] {null, "", " \t\n", "\u00a0\u2007\u202f"}) {
            dream.update(false, null, false, null, true, "🌙".repeat(20));
            assertThat(dream.getTitle()).isEqualTo("🌙".repeat(20));
            dream.update(false, null, false, null, true, clear);
            assertThat(dream.getTitle()).isNull();
            assertThat(dream.getSourceRevision()).isEqualTo(source);
        }
        assertThatThrownBy(() -> dream.update(false, null, false, null, true, "🌙".repeat(21)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void validatesEmotionsBeforeMutationAndTreatsEqualSetsAsNoOp() {
        var dream = completed();
        long source = dream.getSourceRevision();
        assertThatThrownBy(
                        () ->
                                dream.update(
                                        true,
                                        "바뀐 원문",
                                        true,
                                        List.of(DreamEmotion.SAD, DreamEmotion.SAD),
                                        true,
                                        "바뀐 제목"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(DreamErrorCode.INVALID_EMOTIONS));
        assertThat(dream.getOriginalText()).isEqualTo("원문");
        assertThat(dream.getTitle()).isNull();
        assertThat(dream.getSourceRevision()).isEqualTo(source);
        assertThat(dream.isEdited()).isFalse();
        dream.update(
                false, null, true, List.of(DreamEmotion.CALM, DreamEmotion.HAPPY), false, null);
        assertThat(dream.isEdited()).isFalse();
        assertThat(dream.getSourceRevision()).isEqualTo(source);
        dream.update(true, "바뀐 원문", true, List.of(DreamEmotion.SAD), true, "바뀐 제목");
        assertThat(dream.getEmotions()).containsExactly(DreamEmotion.SAD);
        assertThat(dream.getSourceRevision()).isEqualTo(source + 1);
        assertThat(dream.isEdited()).isTrue();
    }

    @Test
    void failedTitleValidationDoesNotPartiallySaveText() {
        var dream = completed();
        long source = dream.getSourceRevision();
        assertThatThrownBy(() -> dream.update(true, "다른 원문", false, null, true, "가".repeat(21)))
                .isInstanceOf(BusinessException.class);
        assertThat(dream.getOriginalText()).isEqualTo("원문");
        assertThat(dream.getSourceRevision()).isEqualTo(source);
    }

    @Test
    void sourceRevisionDetectsEditAndRestoreButIgnoresNoOpAndTitle() {
        var dream = completed();
        long source = dream.getSourceRevision();
        dream.update(true, "원문", false, null, false, null);
        assertThat(dream.getSourceRevision()).isEqualTo(source);
        dream.update(false, null, false, null, true, "내 제목");
        assertThat(dream.getSourceRevision()).isEqualTo(source);
        dream.update(true, "다른 원문", false, null, false, null);
        dream.update(true, "원문", false, null, false, null);
        assertThat(dream.getSourceRevision()).isEqualTo(source + 2);
        assertThat(dream.getTitle()).isEqualTo("내 제목");
    }

    @Test
    void actualTitleRemovalBlocksGeneratorButClearingMissingTitleIsNoOp() {
        var dream = completed();
        dream.update(false, null, false, null, true, "");
        assertThat(dream.isEdited()).isFalse();
        dream.update(false, null, false, null, true, "내 제목");
        dream.update(false, null, false, null, true, "");
        dream.applyGeneratedTitle("자동 제목", dream.getSourceRevision());
        assertThat(dream.getTitle()).isNull();
        assertThat(dream.isEdited()).isTrue();
    }

    @Test
    void keepsLegacyLongTitlesUntilUserActuallyChangesThem() throws Exception {
        var dream = completed();
        var field = Dream.class.getDeclaredField("title");
        field.setAccessible(true);
        String legacy = "가".repeat(100);
        field.set(dream, legacy);
        dream.update(true, "다른 원문", false, null, false, null);
        dream.update(false, null, false, null, true, legacy);
        assertThat(dream.getTitle()).isEqualTo(legacy);
        dream.update(false, null, false, null, true, "짧은 제목");
        assertThat(dream.getTitle()).isEqualTo("짧은 제목");
    }

    @Test
    void draftSubmissionDoesNotChangeInputVersionAndInitialEmotionsDo() {
        var dream =
                Dream.draft(User.create("draft@test.com", "테스터"), "", LocalDate.of(2026, 10, 6));
        dream.saveDraft("꿈");
        long source = dream.getSourceRevision();
        dream.saveDraft("꿈");
        dream.submit();
        assertThat(dream.getSourceRevision()).isEqualTo(source);
        dream.complete(List.of(DreamEmotion.HAPPY));
        assertThat(dream.getSourceRevision()).isEqualTo(source + 1);
    }
}
