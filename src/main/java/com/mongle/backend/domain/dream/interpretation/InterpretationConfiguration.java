package com.mongle.backend.domain.dream.interpretation;

import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class InterpretationConfiguration {
    @Bean
    @ConditionalOnMissingBean(InterpretationGenerator.class)
    InterpretationGenerator interpretationGenerator(DreamAiGateway gateway) {
        return new AiGatewayInterpretationGenerator(gateway);
    }
}
