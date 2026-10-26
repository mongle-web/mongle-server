package com.mongle.backend.domain.dream.image;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageRecoveryProperties.class)
public class ImageRecoveryConfiguration {
    @Bean ImageRecoveryWorker imageRecoveryWorker(ImageRecovery recovery, ImageRecoveryProperties properties) {
        return new ImageRecoveryWorker(recovery, properties);
    }
}
