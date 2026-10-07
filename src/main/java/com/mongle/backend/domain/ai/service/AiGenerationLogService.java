package com.mongle.backend.domain.ai.service;

import com.mongle.backend.domain.ai.dto.logging.AiGenerationAttempt;
import com.mongle.backend.domain.ai.entity.AiGenerationLog;
import com.mongle.backend.domain.ai.repository.AiGenerationLogRepository;
import com.mongle.backend.domain.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관측 로그를 독립 트랜잭션으로 저장한다. 호출 도메인의 커밋·롤백과 로그 저장을 분리한다.
 * 이 메서드의 JPA 저장은 동기식이며 Gateway가 전용 저장 스레드에서 호출한다.
 * DB 장애 시 로그 유실은 허용하되 LLM 결과를 실패로 바꾸지 않는 best effort 정책이다.
 */
@Slf4j
@Service
public class AiGenerationLogService {
    private final AiGenerationLogRepository logs;
    private final UserRepository users;
    private final AiGenerationCostCalculator calculator;
    // 생성자에서만 설정하고 공유한다. AOP 자기 호출에 의존하지 않고 트랜잭션 경계를 명시한다.
    private final TransactionTemplate transaction;

    public AiGenerationLogService(AiGenerationLogRepository logs, UserRepository users,
                                  AiGenerationCostCalculator calculator, PlatformTransactionManager manager) {
        this.logs = logs;
        this.users = users;
        this.calculator = calculator;
        this.transaction = new TransactionTemplate(manager);
        // 실행 중인 저장 스레드에서 새 트랜잭션을 시작한다. 기존 트랜잭션이 있다면 그것만 잠시 중단한다.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // SQL 실행에 사용할 시간 제한이다. 풀 연결 획득 대기는 datasource 설정이 별도로 제한한다.
        this.transaction.setTimeout(3);
    }

    public void record(AiGenerationAttempt attempt) {
        try {
            // 비용 계산 오류도 생성 결과를 덮어쓰지 않도록 같은 보호 범위에서 처리한다.
            var cost = calculator.calculate(attempt.modelName(), attempt.usage());
            transaction.executeWithoutResult(status -> {
                // 사용자 조회 SELECT 없이 FK 참조를 만든다. 존재하지 않는 사용자는 DB 제약에서 거절한다.
                var user = users.getReferenceById(attempt.userId());
                // flush로 SQL 오류를 확인한다. 커밋은 executeWithoutResult가 돌아오기 전에 완료한다.
                logs.saveAndFlush(AiGenerationLog.create(user, attempt, cost));
            });
        } catch (RuntimeException exception) {
            // catch를 트랜잭션 바깥에 둬 flush뿐 아니라 커밋 오류도 흡수한다. 안쪽 트랜잭션만 롤백된다.
            // 예외 메시지·스택에는 SQL 값이나 사용자 원문이 섞일 수 있어 안전한 식별자와 오류 종류만 남긴다.
            log.warn("AI 호출 로그 저장 실패: callId={}, attemptNo={}, errorType={}",
                    attempt.callId(), attempt.attemptNo(), exception.getClass().getSimpleName());
        }
    }
}
