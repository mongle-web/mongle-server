package com.mongle.backend.domain.payment;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PayAppPropertiesTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(PayAppProperties.class)
    static class Config {}
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test void absentConfigurationKeepsSafeDefaultsAndCsvOverrideIsBound() {
        runner.run(context -> assertThat(context.getBean(PayAppProperties.class).openPayTypes())
                .containsExactly("card","kakaopay","naverpay","tosspay"));
        runner.withPropertyValues("mongle.payment.payapp.open-pay-types=card,payco")
                .run(context -> assertThat(context.getBean(PayAppProperties.class).openPayTypes())
                        .containsExactly("card","payco"));
    }
    @Test void unsafeAndExplicitlyEmptySettingsFailBeforeEnablingProvider() {
        for(String value:new String[]{"","card,phone","vbank","rbank","card,card"})
            runner.withPropertyValues("mongle.payment.payapp.open-pay-types="+value)
                    .run(context -> assertThat(context).hasFailed());
    }
}
