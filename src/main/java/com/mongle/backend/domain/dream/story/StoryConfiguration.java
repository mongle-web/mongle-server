package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StoryConfiguration {

    @Bean
    @ConditionalOnMissingBean(StoryGenerator.class)
    StoryGenerator aiGatewayStoryGenerator(DreamAiGateway gateway) {
        return new AiGatewayStoryGenerator(gateway);
    }
}
