package com.mongle.backend.domain.auth;

import com.mongle.backend.domain.auth.entity.SocialProvider;
import com.mongle.backend.domain.auth.oauth.SocialIdentity;
import com.mongle.backend.domain.auth.repository.SocialAccountRepository;
import com.mongle.backend.domain.auth.service.SocialLoginService;
import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:oauth-contract;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class SocialLoginIntegrationTest {
    @Autowired private SocialLoginService login;
    @Autowired private UserService userService;
    @Autowired private UserRepository users;
    @Autowired private SocialAccountRepository accounts;

    @Test
    void registersWithoutNicknameAndKeepsOnboardingOnRepeatedLogin() {
        SocialIdentity identity = identity(SocialProvider.GOOGLE);
        UserResponse first = login.login(identity);
        assertThat(first.nickname()).isNull();
        assertThat(first.onboardingCompleted()).isFalse();
        userService.completeOnboarding(first.userId(), "몽글이");
        UserResponse next = login.login(identity);
        assertThat(next.userId()).isEqualTo(first.userId());
        assertThat(next.nickname()).isEqualTo("몽글이");
        assertThat(next.onboardingCompleted()).isTrue();
    }

    @Test
    void sameEmailDoesNotMergeDifferentProviders() {
        SocialIdentity google = identity(SocialProvider.GOOGLE);
        var kakao = new SocialIdentity(SocialProvider.KAKAO, google.providerUserId(), google.email());
        assertThat(login.login(google).userId()).isNotEqualTo(login.login(kakao).userId());
    }

    @Test
    void simultaneousRegistrationCreatesOneAccountAndNoOrphanUsers() throws Exception {
        SocialIdentity identity = identity(SocialProvider.KAKAO);
        int concurrency = 6;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            List<Future<UserResponse>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 가입 테스트의 시작 대기 시간이 초과되었습니다.");
                    }
                    return login.login(identity);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<UserResponse> future : futures) {
                ids.add(future.get(20, TimeUnit.SECONDS).userId());
            }
            assertThat(ids.stream().distinct().toList()).hasSize(1);
            assertThat(users.findAll().stream().filter(user -> user.getEmail().equals(identity.email())).toList())
                    .hasSize(1);
            assertThat(accounts.findByProviderAndProviderUserId(identity.provider(), identity.providerUserId()))
                    .isPresent();
        } finally {
            start.countDown();
        }
    }

    private SocialIdentity identity(SocialProvider provider) {
        String suffix = UUID.randomUUID().toString();
        return new SocialIdentity(provider, suffix, suffix + "@example.com");
    }
}
