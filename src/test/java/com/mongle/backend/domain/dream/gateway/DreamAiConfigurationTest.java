package com.mongle.backend.domain.dream.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.ai.gateway.AiGateway;
import com.mongle.backend.domain.ai.liner.config.LinerProperties;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class DreamAiConfigurationTest {

    @Test
    void refusesCallBudgetAtOrBeyondAttemptLease() {
        var liner = mock(LinerProperties.class);
        var gateway = mock(AiGateway.class);
        var configuration = new DreamAiConfiguration();

        when(liner.totalTimeout()).thenReturn(Duration.ofMinutes(2));
        assertThatThrownBy(
                        () ->
                                configuration.dreamAiGateway(
                                        gateway, liner, new DreamAiProperties(4)))
                .isInstanceOf(IllegalArgumentException.class);
        when(liner.totalTimeout()).thenReturn(Duration.ofMinutes(3));
        assertThatThrownBy(
                        () ->
                                configuration.dreamAiGateway(
                                        gateway, liner, new DreamAiProperties(4)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyKeyDisablesDomainGenerationButAllowsConfiguration() {
        var liner = mock(LinerProperties.class);
        when(liner.totalTimeout()).thenReturn(Duration.ofSeconds(60));
        when(liner.apiKey()).thenReturn("");

        var client =
                new DreamAiConfiguration()
                        .dreamAiGateway(mock(AiGateway.class), liner, new DreamAiProperties(4));

        assertThat(client.available()).isFalse();
    }
}
