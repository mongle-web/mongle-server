package com.mongle.backend.domain.world.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.dream.analysis.DreamStructureService;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.world.composition.*;
import com.mongle.backend.domain.world.folder.*;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.support.NativeMysqlSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 실제 MySQL COUNT(DISTINCT), 삭제 트랜잭션과 복구 후 과금을 DML 전용 계정으로 검증한다. */
@SpringBootTest(properties = {"mongle.credit.enabled=true", "mongle.image.styles[0]=test-style"})
@ActiveProfiles("mysql")
@Import(WorldImageIntegrationTest.Config.class)
class WorldImageMinimumMysql84Test {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "payment_test");
    }

    @Autowired UserRepository users;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired WorldFolderService folders;
    @Autowired WorldCompositionTransactions worlds;
    @Autowired WorldCompositionWorker worker;
    @Autowired WorldImageService images;
    @Autowired WorldImageTransactions transactions;
    @Autowired CreditService credits;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorldImageIntegrationTest.FakeGenerator generator;

    @Test
    void rejectsSinglePublishedSourceAndResumesFreeThenPaidAfterPublishingPair() throws Exception {
        Long user =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "MySQL세계관"))
                        .getId();
        credits.grant(user, CreditSource.PROMOTION, "minimum-world:" + user, 10);
        var a = ready(user, 1);
        var b = ready(user, 2);
        var c = ready(user, 3);
        var f = folders.create(user, "Minimum");
        folders.select(user, f.worldId(), a.dreamId(), f.revision());
        folders.select(user, f.worldId(), b.dreamId(), folders.get(user, f.worldId()).revision());
        build(user, f.worldId());
        var first = await(images.generate(user, f.worldId(), request(user, f.worldId(), false)));
        assertThat(credits.balance(user).available()).isEqualTo(7);
        dreams.delete(user, b.dreamId(), b.revision());
        folders.select(user, f.worldId(), c.dreamId(), folders.get(user, f.worldId()).revision());
        var before = jdbc.queryForMap("select * from world_images where id=?", first.imageId());
        var sources =
                jdbc.queryForList(
                        "select * from world_image_sources where image_id=? order by id",
                        first.imageId());
        assertThatThrownBy(
                        () -> images.generate(user, f.worldId(), request(user, f.worldId(), true)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldImageErrorCode.COMPOSITION_REQUIRED);
        assertThat(jdbc.queryForMap("select * from world_images where id=?", first.imageId()))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForList(
                                "select * from world_image_sources where image_id=? order by id",
                                first.imageId()))
                .isEqualTo(sources);
        assertThat(generator.calls).hasValue(1);
        assertThat(transactions.latest(user, f.worldId()).freeRegenerationAvailable()).isTrue();
        assertThat(images.downloadUrl(user, f.worldId()).url()).startsWith("https://");
        build(user, f.worldId());
        var free = await(images.generate(user, f.worldId(), request(user, f.worldId(), true)));
        assertThat(free.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(free.freeRegenerationAvailable()).isFalse();
        assertThat(credits.balance(user).available()).isEqualTo(7);
        await(images.generate(user, f.worldId(), request(user, f.worldId(), true)));
        assertThat(credits.balance(user).available()).isEqualTo(6);
        assertThat(generator.calls).hasValue(3);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from credit_ledger where user_id=? and"
                                    + " kind='SETTLE'",
                                Long.class,
                                user))
                .isEqualTo(2);
    }

    private DreamResponse ready(Long user, int daysAgo) {
        var d =
                dreams.create(
                        user,
                        new DreamCreateRequest(
                                LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(daysAgo),
                                "바다를 날았다"));
        d =
                dreams.complete(
                        user,
                        d.dreamId(),
                        new DreamEmotionsRequest(d.revision(), List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user, d.dreamId(), d.revision()));
        d = dreams.get(user, d.dreamId());
        await(stories.generate(user, d.dreamId(), new StoryRequest(d.revision(), false, null)));
        return dreams.get(user, d.dreamId());
    }

    private WorldImageRequest request(Long user, Long world, boolean regenerate) {
        return new WorldImageRequest(
                worlds.get(user, world).compositionRevision(),
                "test-style",
                null,
                regenerate,
                regenerate ? transactions.latest(user, world).imageVersion() : null);
    }

    private void build(Long user, Long world) throws Exception {
        worlds.build(
                user,
                world,
                new WorldBuildRequest(
                        folders.get(user, world).revision(),
                        worlds.get(user, world).compositionRevision()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            worker.poll();
            var state = worlds.get(user, world);
            if (state.status() == GenerationStatus.COMPLETED) return;
            if (state.status() == GenerationStatus.FAILED)
                throw new AssertionError(state.failureCode());
            Thread.sleep(5);
        }
        throw new AssertionError("세계관 완성 대기 시간 초과");
    }
}
