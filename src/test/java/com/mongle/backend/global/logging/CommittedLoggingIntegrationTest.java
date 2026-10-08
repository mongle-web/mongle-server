package com.mongle.backend.global.logging;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.service.UserService;
import com.mongle.backend.global.logging.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 서비스 반환과 실제 커밋을 구분해 잘못된 성공 로그와 멱등 재요청의 중복 기록을 방지한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:mongle-committed-logging;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class CommittedLoggingIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private UserService userService;
    @Autowired private TokenService tokens;
    @Autowired private PlatformTransactionManager manager;

    @Test
    void successLogsRequireCommitAndContainNoPersonalDataOrTokens() {
        var user = users.saveAndFlush(User.register(UUID.randomUUID() + "@example.com"));
        var transaction = new TransactionTemplate(manager);
        try (var auth = new LogCapture(TokenService.class); var onboarding = new LogCapture(UserService.class)) {
            transaction.executeWithoutResult(status -> {
                tokens.login(user.getId());
                userService.completeOnboarding(user.getId(), "비공개닉네임");
                assertThat(auth.messages()).isEmpty();
                assertThat(onboarding.messages()).isEmpty();
                status.setRollbackOnly();
            });
            assertThat(auth.messages()).isEmpty();
            assertThat(onboarding.messages()).isEmpty();
            assertThat(userService.getMe(user.getId()).onboardingCompleted()).isFalse();
            var issued = transaction.execute(status -> {
                var result = tokens.login(user.getId());
                userService.completeOnboarding(user.getId(), "비공개닉네임");
                return result;
            });
            assertThat(auth.messages()).hasSize(1);
            assertThat(onboarding.messages()).hasSize(1);
            userService.completeOnboarding(user.getId(), "비공개닉네임");
            assertThat(onboarding.messages()).hasSize(1);
            assertThat(String.join("\n", auth.messages()) + String.join("\n", onboarding.messages()))
                    .contains("userId=" + user.getId()).doesNotContain(user.getEmail(), "비공개닉네임",
                            issued.refreshToken(), issued.response().accessToken());
        }
    }
}
