package com.mongle.backend.domain.legal;

import com.mongle.backend.support.NativeMysqlSupport;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The same consent/HTTP/ownership/concurrency contract against the migrated disposable fixture. */
class Mysql84LegalConsentIntegrationTest extends LegalConsentIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "credit_test");
    }
}
