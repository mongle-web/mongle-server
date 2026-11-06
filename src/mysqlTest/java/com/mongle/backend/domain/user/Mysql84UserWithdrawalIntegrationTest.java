package com.mongle.backend.domain.user;

import com.mongle.backend.support.NativeMysqlSupport;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same HTTP/FK/bulk/retention/concurrency contract against the isolated migrated MySQL 8.4 fixture. */
class Mysql84UserWithdrawalIntegrationTest extends UserWithdrawalIntegrationTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry,"credit_test");
    }
}
