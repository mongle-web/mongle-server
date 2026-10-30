package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.support.NativeMysqlSupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

/** 같은 집계·수정·삭제 계약을 실제 MySQL과 DML 전용 계정에서 실행한다. */
@SpringBootTest
@ActiveProfiles("mysql")
@Import(DreamAnalysisIntegrationTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecurringElementsMysql84Test extends RecurringElementsContract {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "credit_test");
    }
}
