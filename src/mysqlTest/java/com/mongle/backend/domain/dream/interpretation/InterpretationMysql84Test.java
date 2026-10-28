package com.mongle.backend.domain.dream.interpretation;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.support.NativeMysqlSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** V1 baseline → V3 → V4 → Hibernate validate 이후 DML 전용 계정의 실제 저장·잠금·삭제를 검증한다. */
@SpringBootTest(properties = "mongle.analysis.interpretation.enabled=true")
@ActiveProfiles("mysql")
@Import(InterpretationMysql84Test.Config.class)
class InterpretationMysql84Test {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "credit_test");
    }

    static final String STRUCTURE =
            """
{"generatedTitle":"바다의 꿈","displayKeywords":["바다"],"elements":[
{"key":"sea","type":"PLACE","name":"바다","description":"바다"}],
"scenes":[{"sequence":1,"content":"바다 위를 날았다","disconnectedFromPrevious":false,"elementKeys":["sea"]}]}
""";

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        StructureGenerator nativeStructureGenerator() {
            return input -> CompletableFuture.completedFuture(STRUCTURE);
        }

        @Bean
        @Primary
        FakeGenerator nativeInterpretationGenerator() {
            return new FakeGenerator();
        }
    }

    static class FakeGenerator implements InterpretationGenerator {
        volatile CompletableFuture<String> call;
        final java.util.concurrent.atomic.AtomicInteger count =
                new java.util.concurrent.atomic.AtomicInteger();

        public CompletableFuture<String> generate(Input input) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            count.incrementAndGet();
            return call;
        }
    }

    @Autowired UserRepository users;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService structures;
    @Autowired InterpretationService service;
    @Autowired InterpretationTransactions transactions;
    @Autowired RecentInterpretationRepository rows;
    @Autowired FakeGenerator generator;
    @Autowired JdbcTemplate jdbc;
    Long user;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    static final String VALID = "{\"interpretation\":\"관찰 자료가 적습니다. 🌙 바다가 등장합니다.\"}";

    @BeforeEach
    void setup() {
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "테스터")).getId();
        generator.call = CompletableFuture.completedFuture(VALID);
        generator.count.set(0);
    }

    DreamResponse analyzed(LocalDate date) {
        var created = dreams.create(user, new DreamCreateRequest(date, "바다 위를 날았다"));
        var completed =
                dreams.complete(
                        user,
                        created.dreamId(),
                        new DreamEmotionsRequest(created.revision(), List.of(DreamEmotion.HAPPY)));
        await(structures.analyze(user, completed.dreamId(), completed.revision()));
        return dreams.get(user, completed.dreamId());
    }

    @Test
    void migratedSchemaSupportsUnicodeReuseRefreshFailureAndSourceDeletionWithDmlOnly() {
        assertThat(
                        jdbc.queryForObject(
                                "select success from flyway_schema_history where version='4'",
                                Boolean.class))
                .isTrue();
        var dream = analyzed(today);
        var first = await(service.ensure(user));
        assertThat(first.interpretation()).contains("🌙");
        assertThat(
                        jdbc.queryForObject(
                                "select body from recent_interpretations where user_id=?",
                                String.class,
                                user))
                .isEqualTo(first.interpretation());
        analyzed(today.minusDays(1));
        generator.call = CompletableFuture.failedFuture(new IllegalStateException("private"));
        var failed = await(service.refresh(user));
        assertThat(failed.interpretation()).isEqualTo(first.interpretation());
        assertThat(failed.refreshNeeded()).isTrue();
        generator.call = CompletableFuture.completedFuture(VALID);
        assertThat(await(service.refresh(user)).sources()).hasSize(2);
        dreams.delete(user, dream.dreamId(), dream.revision());
        assertThat(transactions.get(user).interpretation()).isNull();
        assertThat(rows.findByUserId(user).orElseThrow().getResultSources()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from credit_accounts where user_id=?",
                                Long.class,
                                user))
                .isZero();
    }

    @Test
    void mysqlUserRowLockPreventsDuplicateInitialReservations() throws Exception {
        analyzed(today);
        generator.call = new CompletableFuture<>();
        var pending = generator.call;
        try (var executor = Executors.newFixedThreadPool(4)) {
            var gate = new CountDownLatch(1);
            var calls = new ArrayList<Future<CompletableFuture<InterpretationResponse>>>();
            for (int i = 0; i < 4; i++)
                calls.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return service.ensure(user);
                                }));
            gate.countDown();
            var responses = new ArrayList<CompletableFuture<InterpretationResponse>>();
            for (var call : calls) responses.add(call.get(10, TimeUnit.SECONDS));
            assertThat(generator.count).hasValue(1);
            pending.complete(VALID);
            for (var response : responses) await(response);
            assertThat(transactions.get(user).status())
                    .isEqualTo(InterpretationResponse.State.COMPLETED);
        }
    }
}
