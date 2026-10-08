package com.mongle.backend.domain.dream.generation;

import com.mongle.backend.domain.dream.analysis.DreamStructureService;
import com.mongle.backend.domain.dream.gateway.DreamAiProperties;
import com.mongle.backend.domain.dream.story.DreamStoryService;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DreamGenerationProperties.class)
public class DreamGenerationConfiguration {
    @Bean
    DreamGenerationWorker dreamGenerationWorker(
            DreamGenerationTransactions transactions,
            DreamStructureService analysis,
            DreamStoryService story,
            DreamGenerationProperties properties,
            DreamAiProperties ai,
            Clock authClock) {
        return new DreamGenerationWorker(transactions, analysis, story, properties, ai, authClock);
    }
}
