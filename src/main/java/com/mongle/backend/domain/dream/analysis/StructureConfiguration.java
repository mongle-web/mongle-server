package com.mongle.backend.domain.dream.analysis;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.mongle.backend.global.error.BusinessException;

@Configuration
public class StructureConfiguration {
    @Bean
    @ConditionalOnMissingBean(StructureGenerator.class)
    StructureGenerator unavailableStructureGenerator() {
        return new StructureGenerator() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String generate(Input input) {
                throw new BusinessException(AnalysisErrorCode.UNAVAILABLE);
            }
        };
    }
}
