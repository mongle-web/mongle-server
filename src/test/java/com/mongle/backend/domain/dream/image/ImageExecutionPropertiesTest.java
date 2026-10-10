package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

class ImageExecutionPropertiesTest {
    @Test
    void missingConfigurationSeparatesSixteenCallsFromTwoThreads() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of()));
        var properties = binder.bindOrCreate(
                "mongle.image.execution", Bindable.of(ImageExecutionProperties.class));
        assertThat(properties.maxConcurrentCalls()).isEqualTo(16);
        assertThat(properties.resultThreads()).isEqualTo(2);
    }

    @Test
    void operatingConfigurationCanOverrideDefault() {
        var binder = new Binder(new MapConfigurationPropertySource(
                Map.of("mongle.image.execution.max-concurrent-calls", "32",
                        "mongle.image.execution.result-threads", "4")));
        var properties = binder.bindOrCreate(
                "mongle.image.execution", Bindable.of(ImageExecutionProperties.class));
        assertThat(properties.maxConcurrentCalls()).isEqualTo(32);
        assertThat(properties.resultThreads()).isEqualTo(4);
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThatThrownBy(() -> new ImageExecutionProperties(0, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageExecutionProperties(65, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageExecutionProperties(16, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageExecutionProperties(16, 9))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
