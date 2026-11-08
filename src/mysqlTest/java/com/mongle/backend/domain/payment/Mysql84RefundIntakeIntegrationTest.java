package com.mongle.backend.domain.payment;

import com.mongle.backend.support.NativeMysqlSupport;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles(value = "mysql", inheritProfiles = false)
class Mysql84RefundIntakeIntegrationTest extends RefundIntakeIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "payment_test");
    }
}
