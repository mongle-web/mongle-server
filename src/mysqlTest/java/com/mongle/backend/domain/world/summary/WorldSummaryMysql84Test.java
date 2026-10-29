package com.mongle.backend.domain.world.summary;

import com.mongle.backend.support.NativeMysqlSupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

/** 같은 생성·삭제·동시성 시나리오를 MySQL 8.4와 DML 전용 계정으로도 검증한다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "mongle.world.summary.enabled=true",
            "mongle.world.summary.worker-enabled=false",
            "springdoc.api-docs.enabled=true"
        })
@ActiveProfiles(value = "mysql", inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorldSummaryMysql84Test extends WorldSummaryIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "payment_test");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
    }
}
