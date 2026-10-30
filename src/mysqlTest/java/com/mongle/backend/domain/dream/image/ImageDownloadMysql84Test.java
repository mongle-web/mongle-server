package com.mongle.backend.domain.dream.image;

import com.mongle.backend.support.NativeMysqlSupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest(
        properties = {
            "mongle.credit.enabled=true",
            "mongle.image.styles[0]=test-style",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
        })
@ActiveProfiles("mysql")
@Import(ImageDownloadContract.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImageDownloadMysql84Test extends ImageDownloadContract {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "credit_test");
        // 임시 테스트 DB 연결을 기존 다중 컨텍스트 테스트와 경쟁시키지 않는다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }
}
