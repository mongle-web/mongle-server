package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.time.Duration;
import java.util.Map;

class ImageRecoveryWorkerTest {
    @Test void recoveryIsEnabledByDefaultWithoutEnablingNewGeneration() {
        var properties=new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("mongle.image.recovery", Bindable.of(ImageRecoveryProperties.class));
        assertThat(properties.enabled()).isTrue(); assertThat(properties.batchSize()).isEqualTo(20);
        assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(30));
    }
    @Test void invalidRecoveryBoundsAreRejected() {
        assertThatThrownBy(() -> new ImageRecoveryProperties(true,Duration.ZERO,20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageRecoveryProperties(true,Duration.ofMinutes(6),20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageRecoveryProperties(true,Duration.ofSeconds(30),101)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disabledWorkerDoesNotScheduleRecovery() {
        var recovery=mock(ImageRecovery.class);
        try(var worker=new ImageRecoveryWorker(recovery,new ImageRecoveryProperties(false,Duration.ofSeconds(1),20))) {
            worker.start(); verifyNoInteractions(recovery);
        }
    }
    @Test void readyEventStartsOneAutomaticRecoveryAndClosePreventsRestart() {
        var recovery=mock(ImageRecovery.class);
        var worker=new ImageRecoveryWorker(recovery,new ImageRecoveryProperties(true,Duration.ofMinutes(5),20));
        try {
            worker.start(); worker.start(); verify(recovery,timeout(2000).times(1)).recoverExpired();
        } finally { worker.close(); }
        worker.start(); verify(recovery,times(1)).recoverExpired();
    }
    @Test void failedPollDoesNotStopSubsequentRecovery() {
        var recovery=mock(ImageRecovery.class); when(recovery.recoverExpired()).thenThrow(new IllegalStateException()).thenReturn(1);
        try(var worker=new ImageRecoveryWorker(recovery,new ImageRecoveryProperties(true,Duration.ofSeconds(30),20))) {
            worker.poll(); worker.poll(); verify(recovery,times(2)).recoverExpired();
        }
    }
}
