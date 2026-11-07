package com.mongle.backend.domain.legal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class LegalPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LegalProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test void defaultsDoNotPublishOrEnforceAndIncompleteActivationFailsStartup() {
        runner.run(context -> {
            var properties = context.getBean(LegalProperties.class);
            assertThat(properties.published()).isFalse();
            assertThat(properties.enforce()).isFalse();
        });
        runner.withPropertyValues("mongle.legal.enforce=true")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("mongle.legal.published=true")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("mongle.legal.published=true", "mongle.legal.enforce=true",
                        "mongle.legal.terms-digest=" + "a".repeat(64),
                        "mongle.legal.privacy-digest=" + "b".repeat(64))
                .run(context -> assertThat(context).hasNotFailed());
    }
}
