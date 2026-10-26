package com.mongle.backend.domain.payment;

import com.mongle.backend.support.NativeMysqlSupport;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Executes the existing early-callback/idempotent grant scenario against actual MySQL. */
@Import(PaymentEndToEndTest.Config.class)
@ActiveProfiles(value = "mysql", inheritProfiles = false)
class Mysql84PaymentEndToEndTest extends PaymentEndToEndTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "payment_test");
    }
}
