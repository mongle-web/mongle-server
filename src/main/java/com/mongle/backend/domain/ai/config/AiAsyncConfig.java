package com.mongle.backend.domain.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Gateway와 Client가 공유할 제한된 실행 자원을 Spring 생명주기에 연결한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiAsyncProperties.class)
public class AiAsyncConfig {
    @Bean(destroyMethod = "close")
    public AiAsyncResources aiAsyncResources(AiAsyncProperties properties) {
        return new AiAsyncResources(properties);
    }
}
