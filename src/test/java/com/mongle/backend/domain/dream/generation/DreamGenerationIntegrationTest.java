package com.mongle.backend.domain.dream.generation;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.gateway.DreamAiProperties;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url=jdbc:h2:mem:mongle-generation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Import(DreamGenerationIntegrationTest.Config.class)
class DreamGenerationIntegrationTest {
    private static final String STRUCTURE =
            """
            {"generatedTitle":"바다 위를 날다","displayKeywords":["바다"],"elements":[],
             "scenes":[{"sequence":1,"content":"바다 위를 날았다",
                        "disconnectedFromPrevious":false,"elementKeys":[]}]}
            """;
    private static final String STORY =
            """
            {"sections":[{"sequence":1,"kind":"SCENE","sceneSequence":1,
                          "content":"바다 위를 날았다."}]}
            """;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeStructure fakeStructure() {
            return new FakeStructure();
        }

        @Bean
        @Primary
        FakeStory fakeStory() {
            return new FakeStory();
        }
    }

    static class FakeStructure implements StructureGenerator {
        final AtomicInteger calls = new AtomicInteger();
        volatile CompletableFuture<String> result;
        volatile boolean enabled = true;

        @Override
        public boolean available() {
            return enabled;
        }

        @Override
        public CompletableFuture<String> generate(Input input) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            return result;
        }
    }

    static class FakeStory implements StoryGenerator {
        final AtomicInteger calls = new AtomicInteger();
        volatile CompletableFuture<String> result;
        volatile Input lastInput;

        @Override
        public CompletableFuture<String> generate(Input input) {
            lastInput = input;
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(input.scenes()).hasSize(1);
            calls.incrementAndGet();
            return result;
        }
    }

    @Autowired DreamService dreams;
    @Autowired DreamGenerationTransactions transactions;
    @Autowired DreamGenerationJobRepository jobs;
    @Autowired DreamStructureService analysis;
    @Autowired AnalysisTransactions analysisTransactions;
    @Autowired AnalysisRecordService records;
    @Autowired com.mongle.backend.domain.archive.service.ArchiveService archive;
    @Autowired DreamStoryService story;
    @Autowired StoryTransactions storyTransactions;
    @Autowired UserRepository users;
    @Autowired FakeStructure structureGenerator;
    @Autowired FakeStory storyGenerator;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock authClock;

    @Value("${local.server.port}")
    int port;

    private Long user;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private DreamGenerationWorker worker;

    @BeforeEach
    void setUp() {
        jobs.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "테스터")).getId();
        structureGenerator.calls.set(0);
        structureGenerator.enabled = true;
        structureGenerator.result = CompletableFuture.completedFuture(STRUCTURE);
        storyGenerator.calls.set(0);
        storyGenerator.result = CompletableFuture.completedFuture(STORY);
        worker = newWorker();
    }

    @AfterEach
    void tearDown() throws Exception {
        structureGenerator.result.complete(STRUCTURE);
        storyGenerator.result.complete(STORY);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (processingResults() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        worker.close();
        jobs.deleteAll();
    }

    private long processingResults() {
        return jdbc.queryForObject(
                        "select count(*) from dream_analyses where status='PROCESSING'", Long.class)
                + jdbc.queryForObject(
                        "select count(*) from dream_stories where status='PROCESSING'", Long.class);
    }

    private DreamGenerationWorker newWorker() {
        return new DreamGenerationWorker(
                transactions,
                analysis,
                story,
                new DreamGenerationProperties(false, Duration.ofSeconds(2)),
                new DreamAiProperties(4),
                authClock);
    }

    private DreamResponse completed(Long owner, LocalDate date) {
        var dream = dreams.create(owner, new DreamCreateRequest(date, "바다 위를 날았다"));
        return dreams.complete(
                owner,
                dream.dreamId(),
                new DreamEmotionsRequest(dream.revision(), List.of(DreamEmotion.HAPPY)));
    }

    private DreamResponse completed() {
        return completed(user, today);
    }

    private DreamResponse generated() throws Exception {
        var dream = completed();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        return dreams.get(user, dream.dreamId());
    }

    private DreamResponse editText(DreamResponse dream, String text) {
        var request = new DreamUpdateRequest();
        request.setRevision(dream.revision());
        request.setOriginalText(text);
        return dreams.update(user, dream.dreamId(), request);
    }

    private DreamGenerationResponse regenerate(DreamResponse dream) {
        return transactions.regenerate(
                user,
                dream.dreamId(),
                new DreamRegenerationRequest(dream.revision(), state(dream).generationVersion()));
    }

    @Test
    void editDoesNotCallAiAndRegenerationPublishesAnalysisAndStoryTogether() throws Exception {
        var dream = generated();
        var oldStory = storyTransactions.latest(user, dream.dreamId());
        var changed = editText(dream, "숲을 걸었다");
        assertThat(changed.sourceRevision()).isEqualTo(dream.sourceRevision() + 1);
        assertThat(changed.analysisSourceChanged()).isTrue();
        assertThat(structureGenerator.calls).hasValue(1);
        structureGenerator.result =
                CompletableFuture.completedFuture(
                        STRUCTURE.replace("바다", "숲").replace("날았다", "걸었다"));
        storyGenerator.result = new CompletableFuture<>();
        regenerate(changed);
        pumpUntil(() -> storyGenerator.calls.get() == 2);
        var interim = dreams.get(user, dream.dreamId());
        assertThat(interim.displayKeywords()).containsExactly("바다");
        assertThat(interim.analysisResultRevision()).isEqualTo(dream.sourceRevision());
        assertThat(interim.analysisSourceChanged()).isTrue();
        assertThat(
                        analysisTransactions
                                .get(user, oldStory.analysisId())
                                .scenes()
                                .getFirst()
                                .content())
                .contains("바다");
        assertThat(storyGenerator.lastInput.scenes().getFirst().content()).contains("숲");
        assertThat(storyGenerator.lastInput.originalText()).isEqualTo("숲을 걸었다");
        assertThat(storyTransactions.latest(user, dream.dreamId()).resultVersionId())
                .isEqualTo(oldStory.resultVersionId());
        storyGenerator.result.complete(STORY.replace("바다", "숲").replace("날았다", "걸었다"));
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        var latest = dreams.get(user, dream.dreamId());
        assertThat(latest.displayKeywords()).containsExactly("숲");
        assertThat(latest.analysisSourceChanged()).isFalse();
        assertThat(latest.analysisResultRevision()).isEqualTo(changed.sourceRevision());
        var fresh = storyTransactions.latest(user, dream.dreamId());
        assertThat(fresh.resultVersionId()).isNotEqualTo(oldStory.resultVersionId());
        var old = storyTransactions.version(user, oldStory.storyId(), oldStory.resultVersionId());
        assertThat(old.originalText()).isEqualTo("바다 위를 날았다");
        assertThat(old.analysis().scenes().getFirst().content()).contains("바다");
        assertThat(old.emotions()).containsExactly(DreamEmotion.HAPPY);
        assertThat(old.analysisSettings().provider()).isEqualTo("liner");
        assertThat(old.storySettings().requestedModel()).isNotBlank();
        assertThat(old.storySettings().maxCompletionTokens()).isPositive();
        assertThat(old.sourceChanged()).isTrue();
        assertThat(storyTransactions.versions(user, fresh.storyId(), null, 20).items()).hasSize(2);
    }

    @Test
    void failedStoryPreservesPriorAnalysisAndRetryOnlyRepeatsStory() throws Exception {
        var dream = generated();
        var changed = editText(dream, "숲을 걸었다");
        structureGenerator.result = CompletableFuture.completedFuture(STRUCTURE.replace("바다", "숲"));
        storyGenerator.result =
                CompletableFuture.failedFuture(new IllegalStateException("provider"));
        regenerate(changed);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(dreams.get(user, dream.dreamId()).displayKeywords()).containsExactly("바다");
        assertThat(dreams.get(user, dream.dreamId()).analysisStatus())
                .isEqualTo(GenerationStatus.FAILED);
        assertThat(storyTransactions.latest(user, dream.dreamId()).sections().getFirst().content())
                .contains("바다");
        assertThat(structureGenerator.calls).hasValue(2);
        storyGenerator.result = CompletableFuture.completedFuture(STORY.replace("바다", "숲"));
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(2);
        assertThat(storyGenerator.calls).hasValue(3);
        assertThat(dreams.get(user, dream.dreamId()).displayKeywords()).containsExactly("숲");
    }

    @Test
    void publicationStorageFailureRollsBackNewVersionAndAnalysisTogether() throws Exception {
        var dream = generated();
        var old = storyTransactions.latest(user, dream.dreamId());
        var changed = editText(dream, "숲을 걸었다");
        structureGenerator.result = CompletableFuture.completedFuture(STRUCTURE.replace("바다", "숲"));
        storyGenerator.result = CompletableFuture.completedFuture(STORY.replace("바다", "숲"));
        jdbc.execute(
                "ALTER TABLE dream_analysis_display_keywords ADD CONSTRAINT reject_forest CHECK"
                        + " (analysis_id <> "
                        + old.analysisId()
                        + " OR keyword <> '숲')");
        try {
            regenerate(changed);
            pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
            assertThat(dreams.get(user, dream.dreamId()).displayKeywords()).containsExactly("바다");
            assertThat(storyTransactions.latest(user, dream.dreamId()).resultVersionId())
                    .isEqualTo(old.resultVersionId());
            assertThat(storyTransactions.versions(user, old.storyId(), null, 20).items())
                    .hasSize(1);
            assertThat(
                            analysisTransactions
                                    .get(user, old.analysisId())
                                    .scenes()
                                    .getFirst()
                                    .content())
                    .contains("바다");
        } finally {
            jdbc.execute(
                    "ALTER TABLE dream_analysis_display_keywords DROP CONSTRAINT reject_forest");
        }
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(2);
        assertThat(storyGenerator.calls).hasValue(3);
        assertThat(storyTransactions.versions(user, old.storyId(), null, 20).items()).hasSize(2);
        assertThat(dreams.get(user, dream.dreamId()).displayKeywords()).containsExactly("숲");
    }

    @Test
    void regenerationBlocksEditsAndDeletionFromReservationUntilTerminalState() throws Exception {
        var dream = generated();
        var changed = editText(dream, "숲을 걸었다");
        regenerate(changed);
        var edit = new DreamUpdateRequest();
        edit.setRevision(changed.revision());
        edit.setTitle("새 제목");
        assertThatThrownBy(() -> dreams.update(user, changed.dreamId(), edit))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(DreamErrorCode.GENERATION_IN_PROGRESS));
        assertThatThrownBy(() -> dreams.delete(user, changed.dreamId(), changed.revision()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(DreamErrorCode.GENERATION_IN_PROGRESS));
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        edit.setRevision(dreams.get(user, changed.dreamId()).revision());
        assertThat(dreams.update(user, changed.dreamId(), edit).title()).isEqualTo("새 제목");
    }

    @Test
    void regenerationWithoutPreviousAnalysisHidesStagedResultUntilStorySucceeds() throws Exception {
        structureGenerator.enabled = false;
        var dream = completed();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).analysisId()).isNull();
        structureGenerator.enabled = true;
        storyGenerator.result = new CompletableFuture<>();
        regenerate(dreams.get(user, dream.dreamId()));
        pumpUntil(() -> storyGenerator.calls.get() == 1);
        assertThat(dreams.get(user, dream.dreamId()).displayKeywords()).isEmpty();
        assertThat(records.monthly(user, YearMonth.from(today)).analyzedDreamCount()).isZero();
        assertThat(records.recent(user).dreams()).isEmpty();
        storyGenerator.result.complete(STORY);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(dreams.get(user, dream.dreamId()).displayKeywords()).containsExactly("바다");
        assertThat(records.monthly(user, YearMonth.from(today)).analyzedDreamCount()).isEqualTo(1);
    }

    @Test
    void statsAndArchiveKeepOnePriorSuccessUntilAtomicPublication() throws Exception {
        String withElements =
                STRUCTURE
                        .replace(
                                "\"elements\":[]",
                                "\"elements\":[{\"key\":\"sea\",\"type\":\"PLACE\",\"name\":\"바다\",\"description\":\"바다\"}]")
                        .replace("\"elementKeys\":[]", "\"elementKeys\":[\"sea\"]");
        structureGenerator.result = CompletableFuture.completedFuture(withElements);
        var dream = generated();
        var changed = editText(dream, "숲을 걸었다");
        structureGenerator.result =
                CompletableFuture.completedFuture(withElements.replace("바다", "숲"));
        storyGenerator.result = new CompletableFuture<>();
        regenerate(changed);
        pumpUntil(() -> storyGenerator.calls.get() == 2);
        var prior = records.monthly(user, YearMonth.from(today));
        assertThat(prior.analyzedDreamCount()).isEqualTo(1);
        assertThat(prior.keywords()).containsExactly(new AnalysisRecordService.Keyword("바다", 1));
        var interim = archive.detail(user, dream.dreamId()).dream();
        assertThat(interim.displayKeywords()).containsExactly("바다");
        assertThat(interim.analysis().sourceChanged()).isTrue();
        assertThat(interim.generation().status()).isNotEqualTo(DreamGenerationJob.Status.COMPLETED);
        storyGenerator.result.complete(STORY.replace("바다", "숲"));
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        var fresh = records.monthly(user, YearMonth.from(today));
        assertThat(fresh.analyzedDreamCount()).isEqualTo(1);
        assertThat(fresh.keywords()).containsExactly(new AnalysisRecordService.Keyword("숲", 1));
        assertThat(archive.detail(user, dream.dreamId()).dream().analysis().sourceChanged())
                .isFalse();
    }

    @Test
    void duplicateRegenerationReusesJobAndCompletedReplayDoesNotCallAi() throws Exception {
        var dream = generated();
        var request =
                new DreamRegenerationRequest(dream.revision(), state(dream).generationVersion());
        var first = transactions.regenerate(user, dream.dreamId(), request);
        var duplicate = transactions.regenerate(user, dream.dreamId(), request);
        assertThat(duplicate.generationVersion()).isEqualTo(first.generationVersion());
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThatThrownBy(() -> transactions.regenerate(user, dream.dreamId(), request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(DreamErrorCode.VERSION_CONFLICT));
        assertThat(structureGenerator.calls).hasValue(2);
        assertThat(storyGenerator.calls).hasValue(2);
    }

    @Test
    void invalidAnalysisPreservesPreviousResultsAndCanBeRetried() throws Exception {
        var dream = generated();
        var changed = editText(dream, "숲을 걸었다");
        structureGenerator.result = CompletableFuture.completedFuture("{}");
        regenerate(changed);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).failureCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(dreams.get(user, dream.dreamId()).displayKeywords()).containsExactly("바다");
        assertThat(storyGenerator.calls).hasValue(1);
        structureGenerator.result = CompletableFuture.completedFuture(STRUCTURE.replace("바다", "숲"));
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(dreams.get(user, dream.dreamId()).displayKeywords()).containsExactly("숲");
    }

    @Test
    void regenerationRequiresOwnershipAndCurrentRevision() throws Exception {
        var dream = generated();
        var request =
                new DreamRegenerationRequest(dream.revision(), state(dream).generationVersion());
        assertThatThrownBy(() -> transactions.regenerate(Long.MAX_VALUE, dream.dreamId(), request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(DreamErrorCode.NOT_FOUND));
        editText(dream, "숲");
        assertThatThrownBy(() -> transactions.regenerate(user, dream.dreamId(), request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(DreamErrorCode.VERSION_CONFLICT));
        assertThat(structureGenerator.calls).hasValue(1);
    }

    private DreamGenerationResponse state(DreamResponse dream) {
        return transactions.get(user, dream.dreamId());
    }

    private void pumpUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            worker.poll();
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as("자동 생성 상태가 제한 시간 내 전환되어야 한다").isTrue();
    }

    private DreamGenerationTransactions.Candidate candidate(DreamResponse dream) {
        var job = jobs.findByDreamIdAndUserId(dream.dreamId(), user).orElseThrow();
        return new DreamGenerationTransactions.Candidate(job.getId(), user);
    }

    private void expireJob(DreamResponse dream) {
        jdbc.update(
                "update dream_generation_jobs set lease_until=TIMESTAMP '2000-01-01 00:00:00' where"
                        + " dream_id=?",
                dream.dreamId());
    }

    private void dueNow(DreamResponse dream) {
        jdbc.update(
                "update dream_generation_jobs set next_run_at=TIMESTAMP '2000-01-01 00:00:00' where"
                        + " dream_id=?",
                dream.dreamId());
    }

    @Test
    void savesJobAtomicallyAndNeverCallsAiBeforeCommit() {
        var template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(
                status -> {
                    var dream = completed();
                    assertThat(state(dream).status()).isEqualTo(DreamGenerationJob.Status.QUEUED);
                    assertThat(structureGenerator.calls).hasValue(0);
                    status.setRollbackOnly();
                });
        assertThat(jobs.count()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dreams where user_id=?", Long.class, user))
                .isZero();
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(0);
    }

    @Test
    void storesDraftAndEmotionPendingWithoutScheduling() {
        dreams.saveDraft(user, today, new DreamDraftRequest("초안", null, null));
        assertThat(jobs.count()).isZero();
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(0);
    }

    @Test
    void returnsWhileAiIsPendingAndAdvancesAfterTitleEditWithoutSourceChange() throws Exception {
        structureGenerator.result = new CompletableFuture<>();
        var dream = completed();
        assertThat(structureGenerator.calls).hasValue(0);
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(state(dream).status()).isEqualTo(DreamGenerationJob.Status.PROCESSING);
        assertThat(structureGenerator.result).isNotDone();
        var edit = new DreamUpdateRequest();
        edit.setRevision(dream.revision());
        edit.setTitle("내 제목");
        dreams.update(user, dream.dreamId(), edit);
        structureGenerator.result.complete(STRUCTURE);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(storyGenerator.calls).hasValue(1);
        assertThat(dreams.get(user, dream.dreamId()).title()).isEqualTo("내 제목");
        assertThat(state(dream).sourceChanged()).isFalse();
        assertThat(state(dream).analysisId()).isNotNull();
        assertThat(state(dream).storyId()).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dream_images where user_id=?",
                                Long.class,
                                user))
                .isZero();
    }

    @Test
    void otherUserCanStartWhileFirstAiIsPendingButSameUserWaits() {
        structureGenerator.result = new CompletableFuture<>();
        var first = completed();
        var second = completed(user, today.minusDays(1));
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "타인")).getId();
        completed(other, today);
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(2);
        assertThat(state(first).status()).isEqualTo(DreamGenerationJob.Status.PROCESSING);
        assertThat(state(second).status()).isEqualTo(DreamGenerationJob.Status.QUEUED);
    }

    @Test
    void duplicateClaimsAndRetryCannotStartSameAttemptAgain() throws Exception {
        structureGenerator.result = new CompletableFuture<>();
        var dream = completed();
        worker.poll();
        try (var otherWorker = newWorker()) {
            otherWorker.poll();
        }
        assertThat(transactions.claim(candidate(dream))).isEmpty();
        transactions.retry(user, dream.dreamId(), dream.revision());
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(1);
        structureGenerator.result.complete(STRUCTURE);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void analysisFailureKeepsSavedDreamAndRequiresExplicitRetry() throws Exception {
        structureGenerator.result =
                CompletableFuture.failedFuture(new IllegalStateException("테스트 실패"));
        var dream = completed();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).stage()).isEqualTo(DreamGenerationJob.Stage.ANALYSIS);
        assertThat(dreams.get(user, dream.dreamId()).recordStatus())
                .isEqualTo(DreamRecordStatus.COMPLETED);
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(0);
        structureGenerator.result = CompletableFuture.completedFuture(STRUCTURE);
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(2);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void storyRetryReusesCompletedAnalysis() throws Exception {
        storyGenerator.result = CompletableFuture.completedFuture("{}");
        var dream = completed();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).stage()).isEqualTo(DreamGenerationJob.Stage.STORY);
        assertThat(state(dream).failureCode()).isEqualTo("INVALID_OUTPUT");
        var analysisId = state(dream).analysisId();
        storyGenerator.result = CompletableFuture.completedFuture(STORY);
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(state(dream).analysisId()).isEqualTo(analysisId);
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(2);
    }

    @Test
    void unavailableGatewayFailsJobWithoutUndoingSavedDream() throws Exception {
        structureGenerator.enabled = false;
        var dream = completed();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).failureCode()).isEqualTo("UNAVAILABLE");
        assertThat(structureGenerator.calls).hasValue(0);
        assertThat(state(dream).analysisId()).isNull();
        assertThat(dreams.get(user, dream.dreamId()).recordStatus())
                .isEqualTo(DreamRecordStatus.COMPLETED);
    }

    @Test
    void sourceEditBeforeStartStopsJobAndDoesNotScheduleReanalysis() {
        var dream = completed();
        var edit = new DreamUpdateRequest();
        edit.setRevision(dream.revision());
        edit.setOriginalText("숲을 걷는 꿈");
        var changed = dreams.update(user, dream.dreamId(), edit);
        worker.poll();
        assertThat(state(dream).failureCode()).isEqualTo("SOURCE_CHANGED");
        assertThat(state(dream).sourceChanged()).isTrue();
        assertThat(structureGenerator.calls).hasValue(0);
        assertThatThrownBy(() -> transactions.retry(user, dream.dreamId(), changed.revision()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(DreamErrorCode.VERSION_CONFLICT));
    }

    @Test
    void editDuringAnalysisDiscardsOutputAndDoesNotStartStory() throws Exception {
        structureGenerator.result = new CompletableFuture<>();
        var dream = completed();
        worker.poll();
        var edit = new DreamUpdateRequest();
        edit.setRevision(dream.revision());
        edit.setOriginalText("숲을 걷는 꿈");
        dreams.update(user, dream.dreamId(), edit);
        structureGenerator.result.complete(STRUCTURE);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).failureCode()).isEqualTo("SOURCE_CHANGED");
        assertThat(storyGenerator.calls).hasValue(0);
        assertThat(analysisTransactions.get(user, state(dream).analysisId()).scenes()).isEmpty();
    }

    @Test
    void deletionDuringAnalysisRemovesJobAndLateCallbackCannotRecreateIt() throws Exception {
        structureGenerator.result = new CompletableFuture<>();
        var dream = completed();
        worker.poll();
        var analysisId = state(dream).analysisId();
        dreams.delete(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        structureGenerator.result.complete(STRUCTURE);
        pumpUntil(
                () ->
                        analysisTransactions.get(user, analysisId).status()
                                == GenerationStatus.FAILED);
        assertThat(jobs.findByDreamIdAndUserId(dream.dreamId(), user)).isEmpty();
        assertThat(storyGenerator.calls).hasValue(0);
    }

    @Test
    void recoveredClaimWithNoAnalysisStartsSafelyAndRejectsOldCallback() throws Exception {
        var dream = completed();
        var old = transactions.claim(candidate(dream)).orElseThrow();
        expireJob(dream);
        var replacement = transactions.claim(candidate(dream)).orElseThrow();
        transactions.result(old, GenerationStatus.COMPLETED, null);
        assertThat(state(dream).stage()).isEqualTo(DreamGenerationJob.Stage.ANALYSIS);
        assertThat(state(dream).status()).isEqualTo(DreamGenerationJob.Status.PROCESSING);
        expireJob(dream);
        worker.close();
        worker = newWorker();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(replacement.recovering()).isTrue();
        assertThat(structureGenerator.calls).hasValue(1);
    }

    @Test
    void recoveryUsesAlreadyStoredAnalysisWithoutCallingItAgain() throws Exception {
        var dream = completed();
        transactions.claim(candidate(dream)).orElseThrow();
        await(analysis.analyze(user, dream.dreamId(), dream.revision()));
        expireJob(dream);
        worker.close();
        worker = newWorker();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void recoveryUsesAlreadyStoredStoryWithoutRegeneratingEitherStage() throws Exception {
        var dream = completed();
        var analysisClaim = transactions.claim(candidate(dream)).orElseThrow();
        await(analysis.analyze(user, dream.dreamId(), dream.revision()));
        transactions.result(analysisClaim, GenerationStatus.COMPLETED, null);
        transactions.claim(candidate(dream)).orElseThrow();
        await(
                story.generate(
                        user,
                        dream.dreamId(),
                        new StoryRequest(
                                dreams.get(user, dream.dreamId()).revision(), false, null)));
        expireJob(dream);
        worker.close();
        worker = newWorker();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void recoveredFailedAnalysisDoesNotAutomaticallyRepeatProviderCall() throws Exception {
        var dream = completed();
        transactions.claim(candidate(dream)).orElseThrow();
        var reserved = analysisTransactions.begin(user, dream.dreamId(), dream.revision(), true);
        analysisTransactions.fail(reserved.input(), "CALL_FAILED");
        expireJob(dream);
        worker.close();
        worker = newWorker();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).failureCode()).isEqualTo("CALL_FAILED");
        assertThat(structureGenerator.calls).hasValue(0);
        assertThat(storyGenerator.calls).hasValue(0);
    }

    @Test
    void pendingManualAnalysisIsObservedWithoutDuplicateCall() throws Exception {
        structureGenerator.result = new CompletableFuture<>();
        var dream = completed();
        var manual = analysis.analyze(user, dream.dreamId(), dream.revision());
        worker.poll();
        assertThat(state(dream).status()).isEqualTo(DreamGenerationJob.Status.QUEUED);
        assertThat(structureGenerator.calls).hasValue(1);
        structureGenerator.result.complete(STRUCTURE);
        await(manual);
        dueNow(dream);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(1);
    }

    @Test
    void unknownExpiredProviderAttemptRequiresExplicitRetryAfterRestart() throws Exception {
        var dream = completed();
        transactions.claim(candidate(dream)).orElseThrow();
        var reserved = analysisTransactions.begin(user, dream.dreamId(), dream.revision(), true);
        expireJob(dream);
        jdbc.update(
                "update dream_analyses set lease_until=TIMESTAMP '2000-01-01 00:00:00' where id=?",
                reserved.response().analysisId());
        worker.close();
        worker = newWorker();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).failureCode()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(structureGenerator.calls).hasValue(0);
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        var late =
                analysisTransactions.finish(
                        reserved.input(), new StructureValidator().parse(STRUCTURE));
        assertThat(late.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(late.scenes()).isEmpty();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(1);
    }

    @Test
    void expiredStoryCanRetryWithoutRepeatingAnalysisOrAcceptingOldOutput() throws Exception {
        var dream = completed();
        var analysisClaim = transactions.claim(candidate(dream)).orElseThrow();
        var analyzed = await(analysis.analyze(user, dream.dreamId(), dream.revision()));
        transactions.result(analysisClaim, GenerationStatus.COMPLETED, null);
        transactions.claim(candidate(dream)).orElseThrow();
        var reserved =
                storyTransactions.begin(
                        user,
                        dream.dreamId(),
                        new StoryRequest(dreams.get(user, dream.dreamId()).revision(), false, null),
                        true);
        expireJob(dream);
        jdbc.update(
                "update dream_stories set lease_until=TIMESTAMP '2000-01-01 00:00:00' where id=?",
                reserved.response().storyId());
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).failureCode()).isEqualTo("RECOVERY_REQUIRED");
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        var late =
                storyTransactions.finish(
                        reserved.input(), new StoryValidator().parse(STORY, analyzed.scenes()));
        assertThat(late.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(late.sections()).isEmpty();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void completedJobReportsStaleSourceAfterEditWithoutNewCalls() throws Exception {
        var dream = completed();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        var edit = new DreamUpdateRequest();
        edit.setRevision(dreams.get(user, dream.dreamId()).revision());
        edit.setOriginalText("숲을 걷는 꿈");
        dreams.update(user, dream.dreamId(), edit);
        worker.poll();
        assertThat(state(dream).status()).isEqualTo(DreamGenerationJob.Status.COMPLETED);
        assertThat(state(dream).sourceChanged()).isTrue();
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void apiChecksAuthOwnershipRevisionAndNoStore() throws Exception {
        var dream = completed();
        var token = tokens.login(user).response().accessToken();
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "타인")).getId();
        var stranger = tokens.login(other).response().accessToken();
        try (var client = HttpClient.newHttpClient()) {
            assertStatus(send(client, dream.dreamId(), null, null), 401);
            assertStatus(send(client, dream.dreamId(), stranger, null), 404);
            var found = send(client, dream.dreamId(), token, null);
            assertStatus(found, 200);
            assertThat(found.headers().firstValue("Cache-Control")).contains("no-store");
            for (String invalid : List.of("{}", "{\"revision\":-1}")) {
                assertStatus(send(client, dream.dreamId(), token, invalid), 400);
            }
            assertStatus(
                    send(
                            client,
                            dream.dreamId(),
                            stranger,
                            "{\"revision\":" + dream.revision() + "}"),
                    404);
            assertStatus(send(client, dream.dreamId(), token, "{\"revision\":9999}"), 409);
            var retried =
                    send(client, dream.dreamId(), token, "{\"revision\":" + dream.revision() + "}");
            assertStatus(retried, 202);
            assertThat(retried.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(structureGenerator.calls).hasValue(0);
        }
    }

    @Test
    void regenerationApiValidatesVersionOwnershipAndReusesQueuedRequest() throws Exception {
        var dream = generated();
        var token = tokens.login(user).response().accessToken();
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "타인")).getId();
        var stranger = tokens.login(other).response().accessToken();
        String body =
                "{\"revision\":"
                        + dream.revision()
                        + ",\"generationVersion\":"
                        + state(dream).generationVersion()
                        + "}";
        try (var client = HttpClient.newHttpClient()) {
            assertStatus(send(client, dream.dreamId(), null, body, "/regenerate"), 401);
            assertStatus(send(client, dream.dreamId(), stranger, body, "/regenerate"), 404);
            for (String invalid :
                    List.of(
                            "{}",
                            "{\"revision\":0}",
                            "{\"revision\":0,\"generationVersion\":-1}")) {
                assertStatus(send(client, dream.dreamId(), token, invalid, "/regenerate"), 400);
            }
            assertStatus(
                    send(
                            client,
                            dream.dreamId(),
                            token,
                            "{\"revision\":99999,\"generationVersion\":0}",
                            "/regenerate"),
                    409);
            var accepted = send(client, dream.dreamId(), token, body, "/regenerate");
            assertStatus(accepted, 202);
            assertThat(accepted.headers().firstValue("Cache-Control")).contains("no-store");
            var repeated = send(client, dream.dreamId(), token, body, "/regenerate");
            assertStatus(repeated, 202);
            assertThat(repeated.body()).isEqualTo(accepted.body());
            assertThat(structureGenerator.calls).hasValue(1);
        }
    }

    @Test
    void individualGenerationApisCannotBypassUnifiedRegeneration() throws Exception {
        var dream = generated();
        var changed = editText(dream, "숲을 걸었다");
        regenerate(changed);
        assertThatThrownBy(() -> analysis.analyze(user, dream.dreamId(), changed.revision()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(DreamErrorCode.GENERATION_IN_PROGRESS));
        storyGenerator.result = new CompletableFuture<>();
        pumpUntil(() -> storyGenerator.calls.get() == 2);
        assertThatThrownBy(
                        () ->
                                story.generate(
                                        user,
                                        dream.dreamId(),
                                        new StoryRequest(changed.revision(), false, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(DreamErrorCode.GENERATION_IN_PROGRESS));
        storyGenerator.result.completeExceptionally(new IllegalStateException("provider"));
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThatThrownBy(() -> analysis.analyze(user, dream.dreamId(), changed.revision()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(DreamErrorCode.GENERATION_IN_PROGRESS));
        assertThat(structureGenerator.calls).hasValue(2);
        assertThat(storyGenerator.calls).hasValue(2);
    }

    private HttpResponse<String> send(HttpClient client, Long id, String token, String body)
            throws Exception {
        return send(client, id, token, body, body == null ? "" : "/retry");
    }

    private HttpResponse<String> send(
            HttpClient client, Long id, String token, String body, String suffix) throws Exception {
        String path = "/api/v1/dreams/" + id + "/generation" + suffix;
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertStatus(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    }
}
