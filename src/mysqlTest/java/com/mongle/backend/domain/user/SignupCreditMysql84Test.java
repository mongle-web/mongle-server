package com.mongle.backend.domain.user;

import com.mongle.backend.support.NativeMysqlSupport;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** H2와 같은 가입/지급/롤백 계약을 실제 TLS·DML 전용 MySQL 계정으로 검증한다. */
@ActiveProfiles(value = "mysql", inheritProfiles = false)
class SignupCreditMysql84Test extends SignupCreditIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "credit_test");
    }
}
