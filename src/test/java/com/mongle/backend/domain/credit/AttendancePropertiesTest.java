package com.mongle.backend.domain.credit;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AttendancePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CreditConfiguration.class);

    @Test
    void defaultsToDisabledAndOneCredit() {
        runner.run(context -> assertThat(context.getBean(AttendanceProperties.class))
                .isEqualTo(new AttendanceProperties(false, 1)));
    }

    @Test
    void bindsIndependentAttendanceSettingsAndRejectsNonpositiveAmounts() {
        runner.withPropertyValues("mongle.credit.attendance.enabled=true", "mongle.credit.attendance.amount=2")
                .run(context -> {
                    assertThat(context.getBean(AttendanceProperties.class)).isEqualTo(new AttendanceProperties(true, 2));
                    assertThat(context.getBean(CreditProperties.class).enabled()).isFalse();
                });
        for (String amount : new String[]{"0", "-1"}) {
            runner.withPropertyValues("mongle.credit.attendance.amount=" + amount)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
