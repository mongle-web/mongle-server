package com.mongle.backend.domain.world.summary;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.analysis.StructureValidator;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.story.StoryValidator;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.Test;

import java.util.*;

class WorldSummaryEvidenceTest {
    final WorldSummaryEvidence evidence =
            new WorldSummaryEvidence(new StructureValidator(), new StoryValidator());

    static String analysis(String... keys) {
        return "{\"generatedTitle\":\"꿈\",\"displayKeywords\":"
                + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(keys)
                + ",\"elements\":[],\"scenes\":[{\"sequence\":1,\"content\":\"바다\",\"disconnectedFromPrevious\":false,\"elementKeys\":[]}]}";
    }

    static WorldSummaryEvidence.Row row(long dream, String keywords, String emotions) {
        return new WorldSummaryEvidence.Row(
                new WorldSummaryEvidence.Source(dream, dream + 10, 1, 2, dream - 1, null),
                "{\"sections\":[{\"sequence\":1,\"kind\":\"SCENE\",\"sceneSequence\":1,\"content\":\"바다에서"
                    + " 날았다\"}]}",
                keywords,
                emotions,
                null);
    }

    @Test
    void countsDistinctDreamsNormalizesKeysAndKeepsOnlyRepeatedEvidence() {
        var result =
                evidence.assemble(
                        List.of(
                                row(1, analysis("ＯＣＥＡＮ", "하늘"), "HAPPY,HAPPY,CALM"),
                                row(2, analysis("ocean", "하늘", "학교"), "CALM,HAPPY"),
                                row(3, analysis("ocean", "학교"), "SAD")),
                        "settings",
                        120000);
        assertThat(result.data().commonKeywords())
                .extracting(WorldSummaryContent.Keyword::dreamCount)
                .containsExactly(3L, 2L, 2L);
        assertThat(result.data().recurringEmotions())
                .extracting(WorldSummaryContent.Emotion::code)
                .containsExactly(DreamEmotion.CALM, DreamEmotion.HAPPY);
        assertThat(result.data().recurringEmotions())
                .extracting(WorldSummaryContent.Emotion::dreamCount)
                .containsOnly(2L);
        assertThat(result.data().limitedEvidence()).isFalse();
        assertThat(result.fingerprint()).hasSize(64);
        assertThat(result.toString()).doesNotContain("바다에서");
    }

    @Test
    void oneSourceAndMissingLegacySnapshotsNeverInventPatterns() {
        var one = evidence.assemble(List.of(row(1, analysis("바다"), "HAPPY")), "s", 120000);
        assertThat(one.data().limitedEvidence()).isTrue();
        assertThat(one.data().commonKeywords()).isEmpty();
        assertThat(one.data().recurringEmotions()).isEmpty();
        var legacy =
                evidence.assemble(
                        List.of(row(1, null, null), row(2, analysis("바다"), "HAPPY")), "s", 120000);
        assertThat(legacy.data().limitedEvidence()).isTrue();
        assertThat(legacy.data().commonKeywords()).isEmpty();
    }

    @Test
    void rejectsDuplicateDreamCorruptSnapshotAndOversizedPayload() {
        var r = row(1, analysis("바다"), "HAPPY");
        assertThatThrownBy(() -> evidence.assemble(List.of(r, r), "s", 120000))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldSummaryErrorCode.INPUT_UNAVAILABLE);
        assertThatThrownBy(() -> evidence.assemble(List.of(row(1, "bad", "HAPPY")), "s", 120000))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> evidence.assemble(List.of(row(1, null, "UNKNOWN")), "s", 120000))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> evidence.assemble(List.of(r), "s", 10))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldSummaryErrorCode.TOO_LARGE);
    }

    @Test
    void fingerprintIncludesBothSnapshotAndObservedRevisionAndSettings() {
        var r = row(1, analysis("바다"), "HAPPY");
        var first = evidence.assemble(List.of(r), "s", 120000);
        assertThat(evidence.assemble(List.of(r), "s", 120000).fingerprint())
                .isEqualTo(first.fingerprint());
        assertThat(evidence.assemble(List.of(r), "new", 120000).fingerprint())
                .isNotEqualTo(first.fingerprint());
        var edited =
                new WorldSummaryEvidence.Row(
                        new WorldSummaryEvidence.Source(1L, 11L, 1, 3, 0, null),
                        r.story(),
                        r.analysis(),
                        r.emotions(),
                        null);
        assertThat(evidence.assemble(List.of(edited), "s", 120000).fingerprint())
                .isNotEqualTo(first.fingerprint());
    }
}
