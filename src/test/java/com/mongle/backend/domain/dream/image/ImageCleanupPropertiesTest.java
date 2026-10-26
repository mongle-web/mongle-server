package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;

class ImageCleanupPropertiesTest {
    @Test
    void retryBackoffSaturatesAtTheConfiguredMaximum() {
        var p = new ImageCleanupProperties(false, Duration.ofSeconds(2),20,2,Duration.ofMinutes(2),
                Duration.ofSeconds(30),Duration.ofHours(1),5);
        assertThat(p.retryDelay(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.retryDelay(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.retryDelay(100)).isEqualTo(Duration.ofHours(1));
    }
    @Test
    void rejectsUnboundedOrContradictorySettings() {
        assertThatThrownBy(() -> new ImageCleanupProperties(true, Duration.ZERO,20,2,Duration.ofMinutes(2),Duration.ofSeconds(1),Duration.ofSeconds(2),3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageCleanupProperties(true, Duration.ofSeconds(1),20,0,Duration.ofMinutes(2),Duration.ofSeconds(1),Duration.ofSeconds(2),3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageCleanupProperties(true, Duration.ofSeconds(1),20,2,Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(2),3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageCleanupProperties(true, Duration.ofSeconds(1),20,2,Duration.ofMinutes(2),Duration.ofSeconds(2),Duration.ofSeconds(1),3)).isInstanceOf(IllegalArgumentException.class);
    }
}
