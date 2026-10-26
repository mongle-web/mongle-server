package com.mongle.backend.domain.dream.image.s3;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.dream.image.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

import java.time.*;
import java.util.Map;

class ImageS3ConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ImageCleanupProperties.class)
    static class Dependencies { @Bean Clock authClock() { return Clock.systemUTC(); } }

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ImageConfiguration.class, Dependencies.class);

    @Test void defaultsKeepStorageUnavailableAndDoNotCreateSdkClients() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ImageAssetStore.class);
            assertThat(context.getBean(ImageAssetStore.class).available()).isFalse();
            assertThat(context.getBean(ImageGenerator.class).available()).isFalse();
            assertThat(context.getBeansOfType(S3ImageAssetStore.class)).isEmpty();
            assertThat(context.getBean(ImageS3Properties.class).enabled()).isFalse();
        });
    }

    @Test void enabledConfigurationCreatesSingleS3StoreWithoutResolvingCredentialsOrContactingAws() {
        runner.withPropertyValues("mongle.image.s3.enabled=true", "mongle.image.s3.bucket=mongle-test-images",
                "mongle.image.s3.region=ap-northeast-2").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ImageAssetStore.class);
            var storage = context.getBean(ImageAssetStore.class);
            assertThat(storage).isInstanceOf(S3ImageAssetStore.class);
            assertThat(storage.available()).isTrue();
            assertThat(context.getBean(ImageGenerator.class).available()).isFalse();
        });
    }

    @Test void existingTestOrCustomStoreIsNotReplacedByS3() {
        var custom = mock(ImageAssetStore.class);
        runner.withBean(ImageAssetStore.class, () -> custom)
                .withPropertyValues("mongle.image.s3.enabled=true", "mongle.image.s3.bucket=mongle-test-images",
                        "mongle.image.s3.region=ap-northeast-2").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ImageAssetStore.class);
                    assertThat(context.getBean(ImageAssetStore.class)).isSameAs(custom);
                    verifyNoInteractions(custom);
                });
    }

    @Test void activationRequiresBucketAndRegionAndTimeoutShorterThanCleanupLease() {
        runner.withPropertyValues("mongle.image.s3.enabled=true").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("mongle.image.s3.enabled=true", "mongle.image.s3.bucket=mongle-test-images",
                "mongle.image.s3.region=ap-northeast-2", "mongle.image.cleanup.lease=5s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void propertiesBindConfiguredLimitsAndHideBucketFromToString() {
        var properties = bind(Map.of("enabled","true","bucket","mongle-test-images","region","ap-northeast-2",
                "connect-timeout","500ms","attempt-timeout","1s","total-timeout","3s","max-attempts","3"));
        assertThat(properties.maxAttempts()).isEqualTo(3);
        assertThat(properties.totalTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.toString()).doesNotContain("mongle-test-images","ap-northeast-2");
    }

    @ParameterizedTest @ValueSource(strings={"connect-timeout=0s","attempt-timeout=10s","total-timeout=31s",
            "max-attempts=0","max-attempts=4","connect-timeout=5s"})
    void invalidTimingAndRetryLimitsFailBinding(String input) {
        String[] entry = input.split("=");
        assertThatThrownBy(() -> bind(Map.of(entry[0],entry[1]))).isInstanceOf(BindException.class);
    }

    @ParameterizedTest @ValueSource(strings={"bucket=UPPERCASE","bucket=127.0.0.1","bucket=two..dots",
            "region=https://s3.example","region=invalid"})
    void invalidActiveBucketOrRegionFailsBinding(String input) {
        var map = new java.util.HashMap<>(Map.of("enabled","true","bucket","mongle-test-images","region","ap-northeast-2"));
        String[] entry=input.split("="); map.put(entry[0],entry[1]);
        assertThatThrownBy(() -> bind(map)).isInstanceOf(BindException.class);
    }

    ImageS3Properties bind(Map<String,String> values) {
        var source=new java.util.HashMap<String,String>();
        values.forEach((key,value) -> source.put("mongle.image.s3."+key,value));
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("mongle.image.s3",Bindable.of(ImageS3Properties.class));
    }
}
