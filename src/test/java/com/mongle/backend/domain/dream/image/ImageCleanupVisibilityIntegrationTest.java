package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.dream.analysis.DreamStructureService;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** 기본 격리 수준이 REPEATABLE_READ인 DB에서도 예약이 잠금 대기 뒤 최신 참조를 읽는지 확인한다. */
@SpringBootTest(properties = {
        "mongle.credit.enabled=true", "mongle.credit.dream-image-generation=2", "mongle.credit.dream-image-regeneration=3",
        "spring.datasource.url=jdbc:h2:mem:cleanup-visibility;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.mongle.backend.domain.dream.image.ImageCleanupVisibilityIntegrationTest$ReadObserver",
        "mongle.image.styles[0]=test-style", "mongle.image.cleanup.enabled=false"})
@ActiveProfiles("test")
@Import(ImageCleanupWorkerIntegrationTest.Config.class)
class ImageCleanupVisibilityIntegrationTest {
    public static class ReadObserver implements StatementInspector {
        static volatile Gate gate;
        @Override public String inspect(String sql) {
            var current = gate;
            if (current != null && Thread.currentThread().getName().equals("cleanup-visibility")
                    && sql.contains("from users") && sql.contains("for update")) {
                current.ownerRead.countDown();
                try { assertThat(current.published.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }
            return sql;
        }
    }
    static class Gate {
        final CountDownLatch ownerRead = new CountDownLatch(1);
        final CountDownLatch published = new CountDownLatch(1);
    }
    @Autowired com.mongle.backend.domain.credit.CreditService credits;
    @Autowired UserRepository users;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired DreamImageService images;
    @Autowired ImageCleanupTransactions registration;
    @Autowired ImageCleanupRequestRepository requests;
    @Autowired ImageCleanupWorkTransactions work;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test
    void observesImagePublishedAfterOwnerLookupWhileWaitingForUserLock() throws Exception {
        Long user = users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com", "가시성검증")).getId();
        credits.grant(user, com.mongle.backend.domain.credit.CreditSource.SIGNUP, "image-test:"+user, 1000);
        var draft = dreams.create(user,new DreamCreateRequest(LocalDate.of(2026,10,18),"바다를 보았다"));
        var dream = dreams.complete(user,draft.dreamId(),new DreamEmotionsRequest(draft.revision(),List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user,dream.dreamId(),dream.revision()));
        dream = dreams.get(user,dream.dreamId());
        await(stories.generate(user,dream.dreamId(),new StoryRequest(dream.revision(),false,null)));
        var image = await(images.generate(user,dream.dreamId(),new ImageRequest(dream.revision(),"test-style",null,false,null)));
        String key = "published-after-owner-read";
        registration.recordDiscardedUpload(user,image.imageId(),key);
        Long id = requests.findAll().getFirst().getId();
        var gate = new Gate(); ReadObserver.gate = gate;
        try (var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("cleanup-visibility").factory())) {
            var result = new java.util.concurrent.atomic.AtomicReference<Future<Optional<ImageCleanupWorkTransactions.Claim>>>();
            try {
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    users.findByIdForUpdate(user).orElseThrow();
                    result.set(executor.submit(() -> work.claim(id)));
                    try { assertThat(gate.ownerRead.await(5,TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    jdbc.update("update dream_images set storage_key=? where id=?",key,image.imageId());
                });
            } finally { gate.published.countDown(); }
            assertThat(result.get().get(5,TimeUnit.SECONDS)).isEmpty();
            var protectedRequest = requests.findById(id).orElseThrow();
            assertThat(protectedRequest.getFailureCode()).isEqualTo("REFERENCED");
            assertThat(protectedRequest.getAttemptCount()).isZero();
            assertThat(protectedRequest.getDeletionStartedAt()).isNull();
        } finally { ReadObserver.gate = null; }
    }
}
