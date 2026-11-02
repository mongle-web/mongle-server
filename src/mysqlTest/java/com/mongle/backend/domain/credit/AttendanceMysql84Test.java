package com.mongle.backend.domain.credit;

import com.mongle.backend.support.NativeMysqlSupport;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 실제 TLS·DML 전용 MySQL 계정으로 H2와 동일한 일일 지급·잠금·롤백 계약을 검증한다. */
@ActiveProfiles(value = "mysql", inheritProfiles = false)
class AttendanceMysql84Test extends AttendanceIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "credit_test");
    }
}
