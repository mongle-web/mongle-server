package com.mongle.backend.domain.dream.image;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Configuration
@EnableConfigurationProperties({ImageOptions.class, ImageExecutionProperties.class})
public class ImageConfiguration {
    @Bean(destroyMethod = "close")
    ImageGenerationResources imageGenerationResources(ImageExecutionProperties properties) {
        return new ImageGenerationResources(properties.maxConcurrentCalls());
    }

    @Bean
    @ConditionalOnMissingBean(ImageGenerator.class)
    ImageGenerator unavailableImageGenerator() {
        return new ImageGenerator() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public CompletableFuture<byte[]> generate(Input input) {
                return CompletableFuture.failedFuture(new IllegalStateException("이미지 Provider 미연결"));
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(ImageAssetStore.class)
    ImageAssetStore unavailableImageAssetStore() {
        return new ImageAssetStore() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public void put(String key, ImagePayload payload) {
                throw new IllegalStateException("스토리지 미연결");
            }

            @Override
            public void delete(String key) {
                throw new IllegalStateException("스토리지 미연결");
            }

            @Override
            public DownloadUrl temporaryUrl(String key, Duration lifetime) {
                throw new IllegalStateException("스토리지 미연결");
            }
        };
    }
}
