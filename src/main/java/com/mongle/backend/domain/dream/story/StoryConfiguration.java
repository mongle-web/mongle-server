package com.mongle.backend.domain.dream.story;

import com.mongle.backend.global.error.BusinessException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StoryConfiguration {

    @Bean
    @ConditionalOnMissingBean(StoryGenerator.class)
    StoryGenerator unavailableStoryGenerator() {
        return new StoryGenerator() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String generate(Input input) {
                throw new BusinessException(StoryErrorCode.UNAVAILABLE);
            }
        };
    }
}
