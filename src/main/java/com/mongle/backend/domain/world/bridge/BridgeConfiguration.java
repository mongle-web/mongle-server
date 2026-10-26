package com.mongle.backend.domain.world.bridge;

import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BridgeConfiguration {
    @Bean
    @ConditionalOnMissingBean(BridgeGenerator.class)
    BridgeGenerator bridgeGenerator(DreamAiGateway gateway) {
        return new AiGatewayBridgeGenerator(gateway);
    }
}
