package com.mongle.backend.domain.dream.interpretation;

import static org.assertj.core.api.Assertions.*;
import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:interpretations;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Import(InterpretationIntegrationTest.Config.class)
class InterpretationIntegrationTest {
    static final String STRUCTURE = """
        {"generatedTitle":"바다의 꿈","displayKeywords":["바다"],"elements":[
          {"key":"sea","type":"PLACE","name":"바다","description":"푸른 바다"}],
        "scenes":[{"sequence":1,"content":"바다 위를 날았다","disconnectedFromPrevious":false,"elementKeys":["sea"]}]}
        """;
    @TestConfiguration
    static class Config {
        @Bean @Primary StructureGenerator structureGenerator() {
            return input -> CompletableFuture.completedFuture(STRUCTURE);
        }
        @Bean @Primary MutableClock interpretationClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-28T03:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
    @Autowired InterpretationTransactions transactions;
    @Autowired InterpretationTargets targets;
    @Autowired RecentInterpretationRepository rows;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService structures;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MutableClock clock;
    Long user;
    LocalDate today = LocalDate.of(2026, 10, 28);

    @BeforeEach void setup() {
        clock.now = Instant.parse("2026-10-28T03:00:00Z");
        user = users.saveAndFlush(User.create(UUID.randomUUID()+"@test.invalid", "테스터")).getId();
    }
    DreamResponse analyzed(LocalDate date) {
        var created = dreams.create(user, new DreamCreateRequest(date, "바다 위를 날았다"));
        var completed = dreams.complete(user, created.dreamId(),
                new DreamEmotionsRequest(created.revision(), List.of(DreamEmotion.HAPPY)));
        await(structures.analyze(user, completed.dreamId(), completed.revision()));
        return dreams.get(user, completed.dreamId());
    }
    InterpretationTargets.Target current() {
        return new TransactionTemplate(transactionManager).execute(s -> targets.current(user));
    }
    void storeCompleted() {
        var target = current();
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            var row = RecentInterpretation.create(users.findById(user).orElseThrow());
            row.start(target.fingerprint(), targets.encodeIds(target.dreamIds()), "{}", clock.instant());
            row.finish("바다와 자유로운 이동이 관찰됩니다.", targets.encodeSources(target.sources()), clock.instant());
            rows.saveAndFlush(row);
        });
    }
    @Test void readDoesNotGenerateAndKeepsRecentSelectionRules() {
        assertThat(transactions.get(user).status()).isEqualTo(InterpretationResponse.State.EMPTY);
        for (int i=0;i<12;i++) analyzed(today.minusDays(i));
        analyzed(today.minusDays(30));
        var result = transactions.get(user);
        assertThat(result.status()).isEqualTo(InterpretationResponse.State.NOT_GENERATED);
        assertThat(result.targetCount()).isEqualTo(10);
        assertThat(result.from()).isEqualTo(today.minusDays(29));
        assertThat(result.limitedEvidence()).isFalse();
        assertThat(rows.findByUserId(user)).isEmpty();
    }
    @Test void rangeBoundaryAndEvidenceWarningMatchExistingContext() {
        analyzed(today.minusDays(29));
        assertThat(transactions.get(user).targetCount()).isOne();
        assertThat(transactions.get(user).limitedEvidence()).isTrue();
        clock.now = clock.now.plus(Duration.ofDays(1));
        assertThat(transactions.get(user).status()).isEqualTo(InterpretationResponse.State.EMPTY);
    }
    @Test void storedResultIsPrivateAndChangedInputsNeedRefresh() {
        var dream = analyzed(today);
        storeCompleted();
        assertThat(transactions.get(user).interpretation()).contains("바다");
        assertThat(transactions.get(user).refreshNeeded()).isFalse();
        var update = new DreamUpdateRequest();
        update.setRevision(dream.revision()); update.setOriginalText("별을 보았다");
        dreams.update(user, dream.dreamId(), update);
        assertThat(transactions.get(user).refreshNeeded()).isTrue();
        assertThat(transactions.get(user).interpretation()).contains("바다");
        var other = users.saveAndFlush(User.create(UUID.randomUUID()+"@test.invalid", "다른이")).getId();
        assertThat(transactions.get(other).interpretation()).isNull();
        assertThatThrownBy(() -> transactions.get(Long.MAX_VALUE)).isInstanceOf(BusinessException.class);
        var unregistered = users.saveAndFlush(User.register(UUID.randomUUID()+"@test.invalid")).getId();
        assertThatThrownBy(() -> transactions.get(unregistered)).isInstanceOf(BusinessException.class);
    }
    @Test void deletingSourcePurgesStoredInterpretationWithoutRecreation() {
        var dream = analyzed(today); storeCompleted();
        dreams.delete(user, dream.dreamId(), dream.revision());
        var row = rows.findByUserId(user).orElseThrow();
        assertThat(row.getBody()).isNull();
        assertThat(row.getResultSources()).isNull();
        assertThat(row.isEverCompleted()).isTrue();
        assertThat(transactions.get(user).status()).isEqualTo(InterpretationResponse.State.EMPTY);
        analyzed(today);
        assertThat(transactions.get(user).refreshNeeded()).isTrue();
        assertThat(transactions.get(user).interpretation()).isNull();
    }
}
