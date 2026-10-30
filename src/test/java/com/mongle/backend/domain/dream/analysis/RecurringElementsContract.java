package com.mongle.backend.domain.dream.analysis;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.StoryTransactions;
import com.mongle.backend.domain.dream.story.StoryValidator;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

/** 같은 집계 계약을 H2와 DML 전용 MySQL에서 검증한다. */
abstract class RecurringElementsContract {
    @Autowired UserRepository users;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService structures;
    @Autowired AnalysisTransactions analyses;
    @Autowired StoredAnalysisRepository stored;
    @Autowired StoryTransactions stories;
    @Autowired StoryValidator storyValidator;
    @Autowired AnalysisRecordService monthly;
    @Autowired RecurringElementsService recurring;
    @Autowired DreamAnalysisIntegrationTest.FakeGenerator generator;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    Long user;
    YearMonth month = YearMonth.now(ZoneId.of("Asia/Seoul")).minusMonths(1);
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUpRecurringElements() {
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "반복요소")).getId();
        generator.calls.set(0);
        generator.action = input -> output(List.of("바다"), List.of("친구"), 1);
    }

    DreamResponse complete(Long owner, LocalDate date) {
        var d = dreams.create(owner, new DreamCreateRequest(date, "원문"));
        return dreams.complete(
                owner,
                d.dreamId(),
                new DreamEmotionsRequest(d.revision(), List.of(DreamEmotion.HAPPY)));
    }

    DreamResponse analyzed(
            Long owner, LocalDate date, List<String> places, List<String> characters, int scenes) {
        generator.action = input -> output(places, characters, scenes);
        var d = complete(owner, date);
        assertThat(await(structures.analyze(owner, d.dreamId(), d.revision())).status())
                .isEqualTo(com.mongle.backend.global.common.GenerationStatus.COMPLETED);
        return dreams.get(owner, d.dreamId());
    }

    @Test
    void separatesTypesNormalizesNamesAndCountsEachDreamOnceWithoutSideEffects() {
        analyzed(user, month.atDay(1), List.of("ＳＣＨＯＯＬ"), List.of("school", "한 번만"), 2);
        analyzed(user, month.atDay(2), List.of("  school  "), List.of("school"), 2);
        var before = monthly.monthly(user, month);
        int calls = generator.calls.get();
        var entities =
                jdbc.queryForList("select * from dream_entities where user_id=? order by id", user);
        var result = recurring.monthly(user, month);
        assertThat(result.month()).isEqualTo(month);
        assertThat(result.analyzedDreamCount()).isEqualTo(2);
        assertThat(result.places()).hasSize(1);
        assertThat(DreamElementNames.normalize(result.places().getFirst().name()))
                .isEqualTo("school");
        assertThat(result.places().getFirst().dreamCount()).isEqualTo(2);
        assertThat(result.characters())
                .containsExactly(new RecurringElementsResponse.Element("school", 2));
        assertThat(recurring.monthly(user, month)).isEqualTo(result);
        assertThat(monthly.monthly(user, month)).isEqualTo(before);
        assertThat(generator.calls).hasValue(calls);
        assertThat(
                        jdbc.queryForList(
                                "select * from dream_entities where user_id=? order by id", user))
                .isEqualTo(entities);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from credit_ledger where user_id=?",
                                Long.class,
                                user))
                .isZero();
    }

    @Test
    void filtersDateOwnershipIncompleteInitialFailuresAndDetachedAnalyses() {
        analyzed(user, month.atDay(1), List.of("집"), List.of("가족"), 1);
        analyzed(user, month.atEndOfMonth(), List.of("집"), List.of("가족"), 1);
        analyzed(user, month.minusMonths(1).atEndOfMonth(), List.of("집"), List.of("가족"), 1);
        analyzed(user, month.plusMonths(1).atDay(1), List.of("집"), List.of("가족"), 1);
        Long other =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "다른회원"))
                        .getId();
        analyzed(other, month.atDay(1), List.of("집"), List.of("가족"), 1);
        var draft = analyzed(user, month.atDay(2), List.of("집"), List.of("가족"), 1);
        jdbc.update("update dreams set record_status='DRAFT' where id=?", draft.dreamId());
        var detached = analyzed(user, month.atDay(3), List.of("집"), List.of("가족"), 1);
        jdbc.update(
                "update dream_analyses set dream_id=null where source_dream_id=?",
                detached.dreamId());
        var incomplete = analyzed(user, month.atDay(6), List.of("집"), List.of("가족"), 1);
        jdbc.update(
                "update dreams set record_status='EMOTION_PENDING' where id=?",
                incomplete.dreamId());
        var failed = complete(user, month.atDay(4));
        generator.action =
                input -> {
                    throw new IllegalStateException("fixture failure");
                };
        assertThat(await(structures.analyze(user, failed.dreamId(), failed.revision())).status())
                .isEqualTo(com.mongle.backend.global.common.GenerationStatus.FAILED);
        var pending = complete(user, month.atDay(5));
        analyses.begin(user, pending.dreamId(), pending.revision(), true);
        var result = recurring.monthly(user, month);
        assertThat(result.analyzedDreamCount()).isEqualTo(2);
        assertThat(result.places()).containsExactly(new RecurringElementsResponse.Element("집", 2));
        assertThat(result.characters())
                .containsExactly(new RecurringElementsResponse.Element("가족", 2));
    }

    @Test
    void returnsOnlyRepeatedTopFivePerTypeWithStableTiesAndEmptyResults() {
        assertThat(recurring.monthly(user, month))
                .isEqualTo(new RecurringElementsResponse(month, 0, List.of(), List.of()));
        var places = java.util.stream.IntStream.range(0, 7).mapToObj(i -> "p" + i).toList();
        var people = java.util.stream.IntStream.range(0, 7).mapToObj(i -> "c" + i).toList();
        analyzed(user, month.atDay(1), places, people, 1);
        assertThat(recurring.monthly(user, month).analyzedDreamCount()).isOne();
        assertThat(recurring.monthly(user, month).places()).isEmpty();
        assertThat(recurring.monthly(user, month).characters()).isEmpty();
        analyzed(user, month.atDay(2), places, people, 1);
        analyzed(user, month.atDay(3), List.of("p6", "unique"), List.of("c6", "unique"), 1);
        var result = recurring.monthly(user, month);
        assertThat(result.places())
                .containsExactly(
                        new RecurringElementsResponse.Element("p6", 3),
                        new RecurringElementsResponse.Element("p0", 2),
                        new RecurringElementsResponse.Element("p1", 2),
                        new RecurringElementsResponse.Element("p2", 2),
                        new RecurringElementsResponse.Element("p3", 2));
        assertThat(result.characters())
                .containsExactly(
                        new RecurringElementsResponse.Element("c6", 3),
                        new RecurringElementsResponse.Element("c0", 2),
                        new RecurringElementsResponse.Element("c1", 2),
                        new RecurringElementsResponse.Element("c2", 2),
                        new RecurringElementsResponse.Element("c3", 2));
    }

    @Test
    void preservesPreviousResultsDuringEditsPendingProcessingFailureAndStagingUntilStoryCommits() {
        var a = analyzed(user, month.atDay(1), List.of("바다"), List.of("친구"), 1);
        analyzed(user, month.atDay(2), List.of("바다"), List.of("친구"), 1);
        var old = recurring.monthly(user, month);
        var edit = new DreamUpdateRequest();
        edit.setRevision(a.revision());
        edit.setOriginalText("수정한 원문");
        var current = dreams.update(user, a.dreamId(), edit);
        assertThat(recurring.monthly(user, month)).isEqualTo(old);
        requestRegeneration(a.dreamId());
        assertThat(recurring.monthly(user, month)).isEqualTo(old);
        var failed = analyses.beginForSource(user, a.dreamId(), current.sourceRevision(), true);
        assertThat(recurring.monthly(user, month)).isEqualTo(old);
        analyses.fail(failed.input(), "CALL_FAILED");
        assertThat(recurring.monthly(user, month)).isEqualTo(old);
        requestRegeneration(a.dreamId());
        var candidate = analyses.beginForSource(user, a.dreamId(), current.sourceRevision(), true);
        var output = new StructureValidator().parse(output(List.of("숲"), List.of("낯선 사람"), 1));
        analyses.finish(candidate.input(), output);
        assertThat(recurring.monthly(user, month)).isEqualTo(old);
        var story = stories.beginForSource(user, a.dreamId(), current.sourceRevision(), true);
        stories.fail(story.input(), "CALL_FAILED");
        assertThat(recurring.monthly(user, month)).isEqualTo(old);
        story = stories.beginForSource(user, a.dreamId(), current.sourceRevision(), true);
        var completed =
                storyValidator.parse(
                        "{\"sections\":[{\"sequence\":1,\"kind\":\"SCENE\",\"sceneSequence\":1,\"content\":\"숲을"
                            + " 걸었다\"}]}",
                        story.input().scenes());
        stories.finish(story.input(), completed);
        assertThat(recurring.monthly(user, month).analyzedDreamCount()).isEqualTo(2);
        assertThat(recurring.monthly(user, month).places()).isEmpty();
        assertThat(recurring.monthly(user, month).characters()).isEmpty();
        var latest = dreams.get(user, a.dreamId());
        dreams.delete(user, a.dreamId(), latest.revision());
        assertThat(recurring.monthly(user, month).analyzedDreamCount()).isOne();
    }

    @Test
    void legacySuccessWithoutResultRevisionIsCountedButUnnormalizedElementsAreNotInvented() {
        var a = analyzed(user, month.atDay(1), List.of("집"), List.of("가족"), 1);
        analyzed(user, month.atDay(2), List.of("집"), List.of("가족"), 1);
        jdbc.update(
                "update dream_analyses set result_revision=null where source_dream_id=?",
                a.dreamId());
        assertThat(recurring.monthly(user, month).places()).hasSize(1);
        jdbc.update("update dream_entities set normalized_name=null where dream_id=?", a.dreamId());
        var result = recurring.monthly(user, month);
        assertThat(result.analyzedDreamCount()).isEqualTo(2);
        assertThat(result.places()).isEmpty();
        assertThat(result.characters()).isEmpty();
    }

    private void requestRegeneration(Long dream) {
        transactions.executeWithoutResult(
                status ->
                        stored.findBySourceDreamIdAndUserId(dream, user)
                                .orElseThrow()
                                .requestRegeneration());
    }

    String output(List<String> places, List<String> characters, int sceneCount) {
        var elements = new ArrayList<Map<String, Object>>();
        for (var type : List.of(DreamEntityType.PLACE, DreamEntityType.CHARACTER)) {
            for (String name : type == DreamEntityType.PLACE ? places : characters) {
                elements.add(
                        Map.of(
                                "key",
                                "e" + elements.size(),
                                "type",
                                type.name(),
                                "name",
                                name,
                                "description",
                                "근거"));
            }
        }
        var scenes = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= sceneCount; i++)
            scenes.add(
                    Map.of(
                            "sequence",
                            i,
                            "content",
                            "꿈 장면",
                            "disconnectedFromPrevious",
                            false,
                            "elementKeys",
                            elements.stream().map(e -> e.get("key")).toList()));
        return json.writeValueAsString(
                Map.of(
                        "generatedTitle",
                        "꿈 제목",
                        "displayKeywords",
                        List.of("꿈"),
                        "elements",
                        elements,
                        "scenes",
                        scenes));
    }
}
