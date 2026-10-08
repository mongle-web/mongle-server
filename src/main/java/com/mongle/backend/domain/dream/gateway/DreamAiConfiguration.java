package com.mongle.backend.domain.dream.gateway;

import com.mongle.backend.domain.ai.gateway.AiGateway;
import com.mongle.backend.domain.ai.liner.config.LinerProperties;
import com.mongle.backend.domain.dream.analysis.AnalysisTransactions;
import com.mongle.backend.domain.dream.story.StoryTransactions;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DreamAiProperties.class)
public class DreamAiConfiguration {

    @Bean
    DreamGenerationResources dreamGenerationResources(
            DreamAiProperties properties, AnalysisTransactions analysis, StoryTransactions story) {
        // 저장 서비스·DB 자원보다 먼저 완료 작업의 종료를 기다린다.
        return new DreamGenerationResources(properties.maxConcurrentCalls());
    }

    @Bean
    DreamAiGateway dreamAiGateway(
            AiGateway gateway, LinerProperties liner, DreamAiProperties properties) {
        // 분석·서사화의 시도 유효시간(2분)보다 긴 통신 예산은 허용하지 않는다.
        if (liner.totalTimeout().compareTo(Duration.ofMinutes(2)) >= 0) {
            throw new IllegalArgumentException("꿈 AI 전체 호출 제한 시간은 2분 미만이어야 합니다.");
        }
        return new DreamAiGateway(
                gateway, !liner.apiKey().isBlank(), properties.maxConcurrentCalls());
    }
}
