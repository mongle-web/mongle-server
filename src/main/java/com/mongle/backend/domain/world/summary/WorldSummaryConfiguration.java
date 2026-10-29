package com.mongle.backend.domain.world.summary;

import com.mongle.backend.domain.dream.gateway.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorldSummaryProperties.class)
public class WorldSummaryConfiguration {
    @Bean
    @ConditionalOnMissingBean(WorldSummaryGenerator.class)
    WorldSummaryGenerator worldSummaryGenerator(DreamAiGateway gateway) {
        return new AiGatewayWorldSummaryGenerator(gateway);
    }

    @Bean
    WorldSummaryWorker worldSummaryWorker(
            WorldSummaryTransactions transactions,
            WorldSummaryGenerator generator,
            DreamGenerationResources resources,
            WorldSummaryProperties properties) {
        return new WorldSummaryWorker(transactions, generator, resources, properties);
    }
}
