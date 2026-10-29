package com.mongle.backend.domain.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.mongle.backend.domain.auth.entity.SocialProvider;
import com.mongle.backend.domain.auth.oauth.SocialIdentity;
import com.mongle.backend.domain.auth.service.SocialLoginService;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.service.UserService;
import com.mongle.backend.global.error.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.*;
import java.util.concurrent.*;

/** 실제 커밋·잠금을 사용해 온보딩과 가입 혜택을 한 상태 변경으로 검증한다. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:signup-credits;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "mongle.credit.enabled=true"
        })
@ActiveProfiles("test")
class SignupCreditIntegrationTest {
    @Autowired UserRepository users;
    @Autowired UserService onboarding;
    @Autowired CreditService credits;
    @Autowired SocialLoginService login;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired CreditReservationService reservations;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @Autowired java.time.Clock authClock;
    @MockitoSpyBean CreditLedgerRepository ledger;
    @MockitoSpyBean CreditProperties properties;
    Long user;

    @BeforeEach
    void register() {
        user = users.saveAndFlush(User.register(UUID.randomUUID() + "@signup.test")).getId();
    }

    @Test
    void firstCompletionPersistsThreeCreditsAndOneSignupLedgerEntry() {
        assertThat(properties.signupAmount()).isEqualTo(3);
        assertNotCompletedOrGranted();
        var result = onboarding.completeOnboarding(user, "몽글93");
        assertThat(result.onboardingCompleted()).isTrue();
        assertThat(onboarding.getMe(user).nickname()).isEqualTo("몽글93");
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(3, 0, 3));
        assertThat(
                        jdbc.queryForMap(
                                "select source_type,source_key,amount,available,reserved,consumed"
                                        + " from credit_grants where user_id=?",
                                user))
                .containsEntry("source_type", "SIGNUP")
                .containsEntry("source_key", "signup:" + user)
                .containsEntry("amount", 3L)
                .containsEntry("available", 3L)
                .containsEntry("reserved", 0L)
                .containsEntry("consumed", 0L);
        assertThat(
                        jdbc.queryForMap(
                                "select"
                                    + " kind,source_type,available_delta,reserved_delta,available_after,reserved_after"
                                    + " from credit_ledger where user_id=?",
                                user))
                .containsEntry("kind", "GRANT")
                .containsEntry("source_type", "SIGNUP")
                .containsEntry("available_delta", 3L)
                .containsEntry("reserved_delta", 0L)
                .containsEntry("available_after", 3L)
                .containsEntry("reserved_after", 0L);
        assertThat(credits.transactions(user, null, 20).items())
                .singleElement()
                .satisfies(item -> assertThat(item.source()).isEqualTo(CreditSource.SIGNUP));
    }

    @Test
    void oauthLoginAndTokenIssueDoNotGrantBeforeOnboardingOrRegrantAfterwards() {
        String id = UUID.randomUUID().toString();
        var identity = new SocialIdentity(SocialProvider.KAKAO, id, id + "@signup.test");
        user = login.login(identity).userId();
        tokens.login(user);
        assertThat(login.login(identity).onboardingCompleted()).isFalse();
        assertNotCompletedOrGranted();
        onboarding.completeOnboarding(user, "몽글93");
        onboarding.completeOnboarding(user, "몽글93");
        assertThat(login.login(identity).onboardingCompleted()).isTrue();
        tokens.login(user);
        assertThat(credits.balance(user).total()).isEqualTo(3);
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
    }

    @Test
    void concurrentSameNicknameRequestsCommitExactlyOneBenefit() throws Exception {
        var ready = new CountDownLatch(4);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!go.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("시작 대기 시간 초과");
                                    return onboarding.completeOnboarding(user, "몽글93").nickname();
                                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (var future : futures)
                assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("몽글93");
        } finally {
            go.countDown();
        }
        assertThat(credits.balance(user).available()).isEqualTo(3);
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
    }

    @Test
    void concurrentDifferentNicknamesDoNotOverwriteWinnerOrGrantTwice() throws Exception {
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> completeAfter(go, "먼저닉"));
            var second = pool.submit(() -> completeAfter(go, "나중닉"));
            go.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            "OK", UserErrorCode.ONBOARDING_ALREADY_COMPLETED.getCode());
        } finally {
            go.countDown();
        }
        assertThat(onboarding.getMe(user).nickname()).isIn("먼저닉", "나중닉");
        assertThat(credits.balance(user).available()).isEqualTo(3);
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
    }

    private String completeAfter(CountDownLatch start, String nickname) throws Exception {
        if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("시작 대기 시간 초과");
        try {
            onboarding.completeOnboarding(user, nickname);
            return "OK";
        } catch (BusinessException error) {
            return error.getErrorCode().getCode();
        }
    }

    @Test
    void invalidNicknameAndMissingAccountDoNotCreateAnyBenefit() {
        error(() -> onboarding.completeOnboarding(user, "잘못된 닉"), UserErrorCode.INVALID_NICKNAME);
        error(
                () -> onboarding.completeOnboarding(Long.MAX_VALUE, "몽글93"),
                UserErrorCode.USER_NOT_FOUND);
        assertNotCompletedOrGranted();
    }

    @Test
    void legacyCompletedAccountsRemainUnchangedAndAreNotBackfilled() {
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@legacy.test", "기존회원")).getId();
        tokens.login(user);
        onboarding.completeOnboarding(user, "기존회원");
        error(
                () -> onboarding.completeOnboarding(user, "다른회원"),
                UserErrorCode.ONBOARDING_ALREADY_COMPLETED);
        assertThat(credits.balance(user).available()).isZero();
        assertThat(count("credit_accounts")).isZero();
        assertThat(count("credit_grants")).isZero();
        assertThat(count("credit_ledger")).isZero();
    }

    @Test
    void policyChangeDoesNotRegrantAndUsesConfiguredAmountOnlyForFirstCompletion() {
        onboarding.completeOnboarding(user, "몽글93");
        doReturn(5L).when(properties).signupGrantAmount();
        onboarding.completeOnboarding(user, "몽글93");
        assertThat(credits.balance(user).available()).isEqualTo(3);
        assertThat(count("credit_grants")).isOne();
        Long next = users.saveAndFlush(User.register(UUID.randomUUID() + "@signup.test")).getId();
        onboarding.completeOnboarding(next, "새회원93");
        assertThat(credits.balance(next).available()).isEqualTo(5);
    }

    @Test
    void disabledCreditRollsBackNicknameAndDoesNotBlockLegacyIdempotentRequest() {
        doThrow(new BusinessException(CreditErrorCode.UNAVAILABLE))
                .when(properties)
                .requireEnabled();
        error(() -> onboarding.completeOnboarding(user, "몽글93"), CreditErrorCode.UNAVAILABLE);
        assertNotCompletedOrGranted();
        Long legacy =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@legacy.test", "기존회원")).getId();
        assertThat(onboarding.completeOnboarding(legacy, "기존회원").onboardingCompleted()).isTrue();
        assertThat(credits.balance(legacy).available()).isZero();
    }

    @Test
    void failedLedgerWriteRollsBackNicknameAccountGrantAndCanRetryOnce() {
        doThrow(new DataIntegrityViolationException("forced ledger failure")).when(ledger).flush();
        assertThatThrownBy(() -> onboarding.completeOnboarding(user, "몽글93"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertNotCompletedOrGranted();
        org.mockito.Mockito.reset(ledger);
        // 실패 주입을 제거한 뒤 Spring Data 프록시의 원래 위임 동작으로 재시도한다.
        onboarding.completeOnboarding(user, "몽글93");
        assertThat(credits.balance(user).available()).isEqualTo(3);
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
    }

    @Test
    void signupCreditsUseExistingReservationReleaseAndSettlementWithSignupSource() {
        onboarding.completeOnboarding(user, "몽글93");
        var first =
                transaction.execute(
                        status ->
                                reservations.reserve(
                                        user,
                                        "signup-reserve:" + user,
                                        CreditUsage.DREAM_IMAGE_GENERATION,
                                        "signup-image:" + user,
                                        authClock.instant().plusSeconds(120)));
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(0, 3, 3));
        transaction.executeWithoutResult(
                status ->
                        reservations.release(
                                user,
                                first.reservationId(),
                                CreditReservation.ReleaseReason.FAILURE));
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(3, 0, 3));
        var second =
                transaction.execute(
                        status ->
                                reservations.reserve(
                                        user,
                                        "signup-retry:" + user,
                                        CreditUsage.DREAM_IMAGE_GENERATION,
                                        "signup-image-retry:" + user,
                                        authClock.instant().plusSeconds(120)));
        transaction.executeWithoutResult(
                status ->
                        reservations.settle(user, second.reservationId(), "signup-result:" + user));
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(0, 0, 0));
        assertThat(
                        jdbc.queryForMap(
                                "select available,reserved,consumed from credit_grants where"
                                    + " user_id=?",
                                user))
                .containsEntry("available", 0L)
                .containsEntry("reserved", 0L)
                .containsEntry("consumed", 3L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from credit_allocations a join credit_grants g on"
                                    + " g.id=a.grant_id where g.user_id=? and"
                                    + " g.source_type='SIGNUP'",
                                Long.class,
                                user))
                .isEqualTo(2);
    }

    @Test
    void outerTransactionRollbackAlsoRemovesCompletedOnboardingAndBenefit() {
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            onboarding.completeOnboarding(user, "몽글93");
                                            throw new IllegalStateException("outer rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertNotCompletedOrGranted();
    }

    private void assertNotCompletedOrGranted() {
        assertThat(onboarding.getMe(user).onboardingCompleted()).isFalse();
        for (String table : List.of("credit_accounts", "credit_grants", "credit_ledger"))
            assertThat(count(table)).isZero();
    }

    private long count(String table) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where user_id=?", Long.class, user);
    }

    private static void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
