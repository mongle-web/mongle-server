package com.mongle.backend.domain.dream.image;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mongle.backend.domain.dream.image.s3.ImageS3Properties;
import com.mongle.backend.domain.dream.image.openai.OpenAiImageProperties;
import com.mongle.backend.domain.dream.image.openai.OpenAiImageGenerator;
import com.mongle.backend.domain.dream.image.s3.S3ImageAssetStore;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Configuration
@EnableConfigurationProperties({ImageOptions.class, ImageExecutionProperties.class, ImageS3Properties.class, OpenAiImageProperties.class})
public class ImageConfiguration {
    @Bean(destroyMethod = "close")
    ImageGenerationResources imageGenerationResources(ImageExecutionProperties properties, ImageAssetStore storage) {
        // 종료 순서: Provider 통신 → 완료 풀 → 저장소. 진행 중 완료 처리보다 저장소를 먼저 닫지 않는다.
        return new ImageGenerationResources(properties.maxConcurrentCalls(), properties.resultThreads());
    }

    @Bean
    @ConditionalOnMissingBean(ImageGenerator.class)
    ImageGenerator imageGenerator(OpenAiImageProperties properties, ImageOptions options,
            ImageExecutionProperties execution, ImageS3Properties storage, ImageGenerationResources completionResources) {
        if (properties.enabled()) return OpenAiImageGenerator.open(properties, options, execution, storage);
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
    ImageAssetStore imageAssetStore(ImageS3Properties properties, ImageCleanupProperties cleanup,
            ImageExecutionProperties execution, Clock authClock) {
        if (properties.enabled()) return S3ImageAssetStore.open(properties, cleanup, execution, authClock);
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
