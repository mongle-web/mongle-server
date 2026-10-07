package com.mongle.backend.domain.dream.analysis;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;

class AnalysisRecordServiceTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void acceptsLimitsWithinTenDreamMaximum(int limit) {
        assertThatCode(() -> createService(limit)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    void rejectsLimitsOutsideTenDreamMaximum(int limit) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> createService(limit))
                .withMessage("최근 분석 범위 설정이 올바르지 않습니다.");
    }

    private AnalysisRecordService createService(int limit) {
        return new AnalysisRecordService(
                mock(EntityManager.class),
                mock(StoredAnalysisRepository.class),
                mock(AnalysisTransactions.class),
                Clock.systemUTC(),
                30,
                limit);
    }
}
