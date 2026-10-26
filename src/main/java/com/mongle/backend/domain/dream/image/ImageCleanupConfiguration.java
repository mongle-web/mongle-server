package com.mongle.backend.domain.dream.image;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageCleanupProperties.class)
public class ImageCleanupConfiguration {
    @Bean
    ImageCleanupWorker imageCleanupWorker(ImageCleanupWorkTransactions transactions,
            ImageAssetStore storage, ImageCleanupProperties properties) {
        return new ImageCleanupWorker(transactions, storage, properties);
    }
}
