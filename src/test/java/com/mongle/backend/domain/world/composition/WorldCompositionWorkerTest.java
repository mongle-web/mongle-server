package com.mongle.backend.domain.world.composition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.world.bridge.BridgeService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

class WorldCompositionWorkerTest {
    @Test
    void defaultsAndBoundariesAreValidated() {
        var p =
                new Binder(new MapConfigurationPropertySource(Map.of()))
                        .bindOrCreate(
                                "mongle.world.composition",
                                Bindable.of(WorldCompositionProperties.class));
        assertThat(p.enabled()).isTrue();
        assertThat(p.workerEnabled()).isTrue();
        assertThat(p.maxConcurrentCalls()).isEqualTo(2);
        assertThatThrownBy(() -> new WorldCompositionProperties(true, true, Duration.ZERO, 20, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WorldCompositionProperties(
                                        true, true, Duration.ofSeconds(2), 101, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WorldCompositionProperties(
                                        true, true, Duration.ofSeconds(2), 20, 9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void automaticStartIsIdempotentAndDisabledWorkerDoesNotStart() {
        var tx = mock(WorldCompositionTransactions.class);
        var bridges = mock(BridgeService.class);
        try (var worker =
                new WorldCompositionWorker(
                        tx,
                        bridges,
                        new WorldCompositionProperties(
                                true, false, Duration.ofSeconds(2), 20, 2))) {
            worker.start();
            verifyNoInteractions(tx);
        }
        try (var worker =
                new WorldCompositionWorker(
                        tx,
                        bridges,
                        new WorldCompositionProperties(true, true, Duration.ofMinutes(1), 20, 2))) {
            worker.start();
            worker.start();
            verify(tx, timeout(2000).times(1)).runnable();
        }
    }

    @Test
    void inFlightBridgeLimitPreventsUnboundedFanoutAndFailureReleasesSlot() {
        var tx = mock(WorldCompositionTransactions.class);
        var bridges = mock(BridgeService.class);
        var candidate = mock(WorldCompositionRepository.Candidate.class);
        when(candidate.getUserId()).thenReturn(1L);
        when(candidate.getWorldId()).thenReturn(2L);
        var step =
                new WorldCompositionTransactions.Step(
                        1L, 2L, "attempt", "claim", 3L, 4L, 5L, null, false);
        when(tx.runnable()).thenReturn(List.of(candidate));
        when(tx.next(1L, 2L)).thenReturn(step);
        var pending =
                new CompletableFuture<com.mongle.backend.domain.world.bridge.BridgeResponse>();
        when(bridges.generate(any(), any())).thenReturn(pending);
        try (var worker =
                new WorldCompositionWorker(
                        tx,
                        bridges,
                        new WorldCompositionProperties(
                                true, false, Duration.ofSeconds(2), 20, 1))) {
            worker.poll();
            worker.poll();
            assertThat(worker.activeCount()).isEqualTo(1);
            verify(bridges, times(1)).generate(any(), any());
            pending.completeExceptionally(new IllegalStateException());
            assertThat(worker.activeCount()).isZero();
            verify(tx).completed(step, null, true);
        }
    }
}
