package com.mongle.backend.operations.credit;

import com.mongle.backend.global.error.BusinessException;

import org.springframework.dao.DataIntegrityViolationException;

import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TestCreditCommand {
    private TestCreditCommand() {}

    public static boolean requested(String[] arguments) {
        return Arrays.stream(arguments).anyMatch(argument -> argument.startsWith("--test-credit"));
    }

    public static int run(String[] arguments, Map<String, String> environment, PrintStream output) {
        var json = JsonMapper.builder().build();
        // 입력/설정 오류도 DB 연결 전에 처리한다. 원본 예외 메시지는 출력하지 않는다.
        try (var logging = new TestCreditLogging()) {
            TestCreditRequest request;
            try {
                request = TestCreditRequest.parse(arguments);
            } catch (RuntimeException exception) {
                throw new Failure("INVALID_ARGUMENTS");
            }
            var settings = TestCreditSettings.fromEnvironment(environment);
            try (var context =
                    TestCreditContext.open(settings.dataSource(), settings.creditProperties())) {
                var result = context.execute(request);
                var response = new LinkedHashMap<String, Object>();
                response.put("event", "test-credit-completed");
                response.put("operation", request.operation());
                response.put(
                        "status",
                        result.alreadyGranted()
                                ? "ALREADY_GRANTED"
                                : request.operation().equals("grant") ? "GRANTED" : "WOULD_GRANT");
                response.put("userId", request.userId());
                response.put("amount", request.amount());
                response.put("source", "PROMOTION");
                response.put("grantId", result.grantId());
                response.put("balance", result.balance());
                output.println(json.writeValueAsString(response));
                return 0;
            }
        } catch (Exception exception) {
            String code =
                    exception instanceof Failure
                            ? exception.getMessage()
                            : exception instanceof BusinessException business
                                    ? business.getErrorCode().getCode()
                                    : exception instanceof DataIntegrityViolationException
                                            ? "CREDIT_WRITE_CONFLICT"
                                            : "TEST_CREDIT_FAILED";
            output.println(
                    json.writeValueAsString(Map.of("event", "test-credit-failed", "code", code)));
            return 1;
        }
    }

    static final class Failure extends RuntimeException {
        Failure(String code) {
            super(code);
        }
    }
}
