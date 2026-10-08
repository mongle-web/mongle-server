package com.mongle.backend.domain.dream.gateway;

import com.mongle.backend.domain.dream.analysis.AnalysisTransactions;
import com.mongle.backend.domain.dream.story.StoryTransactions;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DreamAiProperties.class)
public class DreamAiConfiguration {

    @Bean
    DreamGenerationResources dreamGenerationResources(
            DreamAiProperties properties, AnalysisTransactions analysis, StoryTransactions story) {
        // 저장 서비스·DB 자원보다 먼저 완료 작업의 종료를 기다린다.
        return new DreamGenerationResources(properties.maxConcurrentCalls());
    }

}
