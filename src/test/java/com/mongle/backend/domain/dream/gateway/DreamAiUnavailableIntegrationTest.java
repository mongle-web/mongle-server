package com.mongle.backend.domain.dream.gateway;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.ai.liner.LinerAiGateway;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mongle-no-ai-key;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "mongle.ai.liner.api-key="
        })
@ActiveProfiles("test")
class DreamAiUnavailableIntegrationTest {

    @Autowired DreamService dreams;
    @Autowired DreamStructureService structure;
    @Autowired DreamStoryService story;
    @Autowired AnalysisTransactions analyses;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean LinerAiGateway liner;
    private Long userId;
    private DreamResponse dream;

    @BeforeEach
    void setUp() {
        userId = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "테스터")).getId();
        var created =
                dreams.create(
                        userId,
                        new DreamCreateRequest(
                                LocalDate.now(ZoneId.of("Asia/Seoul")), "바다 위를 날았다"));
        dream =
                dreams.complete(
                        userId,
                        created.dreamId(),
                        new DreamEmotionsRequest(created.revision(), List.of(DreamEmotion.HAPPY)));
        clearInvocations(liner);
    }

    @Test
    void analysisWithoutKeyDoesNotCreateAttemptOrCallProvider() {
        assertThatThrownBy(
                        () -> await(structure.analyze(userId, dream.dreamId(), dream.revision())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(AnalysisErrorCode.UNAVAILABLE));

        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dream_analyses where user_id=?",
                                Long.class,
                                userId))
                .isZero();
        assertThat(dreams.get(userId, dream.dreamId()).revision()).isEqualTo(dream.revision());
        verify(liner, never()).generate(any());
    }

    @Test
    void storyWithoutKeyKeepsExistingAnalysisAndDoesNotCreateAttempt() {
        // 키가 없어진 운영 환경에서도 기존 분석을 읽을 수 있는 상황을 구성한다.
        var reserved = analyses.begin(userId, dream.dreamId(), dream.revision(), true);
        String output =
                """
                {"generatedTitle":"바다 위를 날다","displayKeywords":["바다"],"elements":[],
                "scenes":[{"sequence":1,"content":"바다 위를 날았다",
                "disconnectedFromPrevious":false,"elementKeys":[]}]}
                """;
        analyses.finish(reserved.input(), new StructureValidator().parse(output));
        var analyzed = dreams.get(userId, dream.dreamId());

        assertThatThrownBy(
                        () ->
                                await(
                                        story.generate(
                                                userId,
                                                dream.dreamId(),
                                                new StoryRequest(
                                                        analyzed.revision(), false, null))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(StoryErrorCode.UNAVAILABLE));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dream_stories where user_id=?",
                                Long.class,
                                userId))
                .isZero();
        assertThat(analyses.get(userId, reserved.input().analysisId()).scenes()).hasSize(1);
        verify(liner, never()).generate(any());
    }
}
