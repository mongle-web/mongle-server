package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StructureConfiguration {
    @Bean
    @ConditionalOnMissingBean(StructureGenerator.class)
    StructureGenerator aiGatewayStructureGenerator(DreamAiGateway gateway) {
        return new AiGatewayStructureGenerator(gateway);
    }
}
