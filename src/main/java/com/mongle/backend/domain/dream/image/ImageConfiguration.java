package com.mongle.backend.domain.dream.image;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(ImageOptions.class)
public class ImageConfiguration {
    @Bean
    @ConditionalOnMissingBean(ImageGenerator.class)
    ImageGenerator unavailableImageGenerator() {
        return new ImageGenerator() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public byte[] generate(Input input) {
                throw new IllegalStateException("이미지 Provider 미연결");
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
