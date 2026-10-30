package com.mongle.backend.domain.dream.analysis;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.*;
import java.util.List;

class AnalysisRecordControllerTest {
    @Test
    void bothMonthlyEndpointsDefaultToKoreanMonthAtUtcMonthBoundary() {
        var records = mock(AnalysisRecordService.class);
        var elements = mock(RecurringElementsService.class);
        var november = YearMonth.of(2026, 11);
        when(records.monthly(1L, november))
                .thenReturn(new AnalysisRecordService.Monthly(november, 0, List.of(), List.of()));
        when(elements.monthly(1L, november))
                .thenReturn(new RecurringElementsResponse(november, 0, List.of(), List.of()));
        var controller =
                new AnalysisRecordController(
                        records,
                        elements,
                        Clock.fixed(Instant.parse("2026-10-31T15:30:00Z"), ZoneOffset.UTC));
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("1").build();
        controller.monthly(jwt, null);
        controller.recurringElements(jwt, null);
        verify(records).monthly(1L, november);
        verify(elements).monthly(1L, november);
    }
}
