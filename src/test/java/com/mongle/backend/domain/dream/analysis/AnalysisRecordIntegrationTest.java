package com.mongle.backend.domain.dream.analysis;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.*;
import java.util.*;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:mongle-records;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Import(DreamAnalysisIntegrationTest.Config.class)
class AnalysisRecordIntegrationTest {
    @Autowired DreamStructureService structure;
    @Autowired DreamService dreams;
    @Autowired UserRepository users;
    @Autowired AnalysisRecordService records;
    @Autowired DreamAnalysisIntegrationTest.FakeGenerator generator;
    Long user;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "테스터")).getId();
        generator.calls.set(0);
        generator.action = input -> StructureValidatorTest.VALID;
    }

    private DreamResponse analyzed(LocalDate date) {
        var draft = dreams.create(user, new DreamCreateRequest(date, "바다 위를 날았다"));
        var completed =
                dreams.complete(
                        user,
                        draft.dreamId(),
                        new DreamEmotionsRequest(draft.revision(), List.of(DreamEmotion.HAPPY)));
        await(structure.analyze(user, completed.dreamId(), completed.revision()));
        return dreams.get(user, completed.dreamId());
    }

    @Test
    void keywordsCountDreamsAndPreserveDeletedAnalysisButNotEmotions() {
        var first = analyzed(today);
        dreams.delete(user, first.dreamId(), first.revision());
        var preserved = records.monthly(user, YearMonth.from(today));
        assertThat(preserved.analyzedDreamCount()).isEqualTo(1);
        assertThat(preserved.keywords())
                .containsExactly(new AnalysisRecordService.Keyword("바다", 1));
        assertThat(preserved.emotions()).isEmpty();
        analyzed(today);
        var both = records.monthly(user, YearMonth.from(today));
        assertThat(both.keywords()).containsExactly(new AnalysisRecordService.Keyword("바다", 2));
        assertThat(both.emotions())
                .containsExactly(new AnalysisRecordService.Emotion(DreamEmotion.HAPPY, 1));
    }

    @Test
    void sameElementAcrossScenesCountsAsOneDream() {
        generator.action =
                input ->
                        StructureValidatorTest.VALID.replace(
                                "}]}",
                                "},{\"sequence\":2,\"content\":\"바다에"
                                    + " 내려왔다\",\"disconnectedFromPrevious\":false,\"elementKeys\":[\"sea\"]}]}");
        analyzed(today);
        assertThat(records.monthly(user, YearMonth.from(today)).keywords())
                .containsExactly(new AnalysisRecordService.Keyword("바다", 1));
    }

    @Test
    void recentUsesThirtyDayWindowAndAtMostTenDreams() {
        for (int i = 0; i < 12; i++) analyzed(today.minusDays(i));
        analyzed(today.minusDays(30));
        var recent = records.recent(user);
        assertThat(recent.from()).isEqualTo(today.minusDays(29));
        assertThat(recent.dreams()).hasSize(10);
        assertThat(recent.dreams().getFirst().dreamedAt()).isEqualTo(today);
        assertThat(recent.dreams().getLast().dreamedAt()).isEqualTo(today.minusDays(9));
        assertThat(recent.limitedEvidence()).isFalse();
    }

    @Test
    void boundaryDateIsIncludedAndFewDreamsHaveLimitedEvidence() {
        analyzed(today.minusDays(29));
        analyzed(today.minusDays(30));
        assertThat(records.recent(user).dreams()).hasSize(1);
        assertThat(records.recent(user).limitedEvidence()).isTrue();
        assertThat(records.monthly(Long.MAX_VALUE, YearMonth.from(today)).keywords()).isEmpty();
        assertThat(records.recent(Long.MAX_VALUE).dreams()).isEmpty();
    }
}
