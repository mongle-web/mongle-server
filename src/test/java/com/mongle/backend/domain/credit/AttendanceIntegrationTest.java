package com.mongle.backend.domain.credit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.service.UserService;
import com.mongle.backend.global.error.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 실제 커밋·사용자 잠금으로 일일 지급과 기존 크레딧 흐름의 정합성을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:attendance;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.credit.enabled=true", "mongle.credit.attendance.enabled=true"
})
@ActiveProfiles("test")
class AttendanceIntegrationTest {
    @Autowired AttendanceService attendance;
    @Autowired CreditService credits;
    @Autowired CreditReservationService reservations;
    @Autowired UserRepository users;
    @Autowired UserService onboarding;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @MockitoBean(name = "authClock") Clock clock;
    @MockitoSpyBean AttendanceProperties properties;
    @MockitoSpyBean CreditProperties creditProperties;
    @MockitoSpyBean CreditLedgerRepository ledger;
    @Value("${local.server.port}") int port;
    final JsonMapper json = JsonMapper.builder().build();
    volatile Instant now;
    Long user;

    @BeforeEach
    void setup() {
        now = Instant.parse("2026-11-02T03:00:00Z");
        when(clock.instant()).thenAnswer(invocation -> now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        user = newUser();
    }

    @Test
    void statusDoesNotCreateAnAccountOrGrantAndSeparatesUsers() {
        assertThat(attendance.status(user)).isEqualTo(new AttendanceStatus(
                LocalDate.of(2026, 11, 2), false, 1, true, Instant.parse("2026-11-02T15:00:00Z")));
        assertNoCreditWrites();
        attendance.claim(newUser());
        assertThat(attendance.status(user).claimed()).isFalse();
        assertNoCreditWrites();
    }

    @Test
    void firstClaimWritesOneGrantAndLedgerWithAttendanceSource() {
        var result = attendance.claim(user);
        assertThat(result.alreadyClaimed()).isFalse();
        assertThat(result.amount()).isOne();
        assertThat(result.balance()).isEqualTo(new CreditBalance(1, 0, 1));
        assertThat(result.nextClaimAt()).isEqualTo(Instant.parse("2026-11-02T15:00:00Z"));
        assertThat(jdbc.queryForMap("select source_type,source_key,amount from credit_grants where user_id=?", user))
                .containsEntry("source_type", "ATTENDANCE")
                .containsEntry("source_key", "attendance:" + user + ":2026-11-02")
                .containsEntry("amount", 1L);
        assertThat(credits.transactions(user, null, 20).items()).singleElement().satisfies(item -> {
            assertThat(item.source()).isEqualTo(CreditSource.ATTENDANCE);
            assertThat(item.kind()).isEqualTo(CreditLedger.Kind.GRANT);
            assertThat(item.availableDelta()).isOne();
            assertThat(item.reservedDelta()).isZero();
        });
        assertThat(attendance.status(user).claimed()).isTrue();
        assertThat(attendance.status(user).claimable()).isFalse();
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
    }

    @Test
    void retryPreservesOriginalAmountButReturnsCurrentBalanceAfterOtherCreditChanges() {
        attendance.claim(user);
        doReturn(5L).when(properties).amount();
        credits.grant(user, CreditSource.PAYMENT, "attendance-order:" + user, 7);
        var retry = attendance.claim(user);
        assertThat(retry.alreadyClaimed()).isTrue();
        assertThat(retry.amount()).isOne();
        assertThat(retry.balance().available()).isEqualTo(8);
        assertThat(attendance.status(user).rewardAmount()).isOne();
        assertThat(count("credit_grants")).isEqualTo(2);
        assertThat(count("credit_ledger")).isEqualTo(2);
        now = Instant.parse("2026-11-02T15:00:00Z");
        assertThat(attendance.status(user).rewardAmount()).isEqualTo(5);
        assertThat(attendance.claim(user).amount()).isEqualTo(5);
        assertThat(credits.balance(user).available()).isEqualTo(13);
    }

    @Test
    void disabledAttendanceBlocksOnlyNewClaimsAndPreservesExistingConfirmation() {
        attendance.claim(user);
        doReturn(false).when(properties).enabled();
        assertThat(attendance.claim(user).alreadyClaimed()).isTrue();
        assertThat(attendance.status(user).claimed()).isTrue();
        now = now.plus(Duration.ofDays(1));
        assertThat(attendance.status(user).claimable()).isFalse();
        error(() -> attendance.claim(user), CreditErrorCode.UNAVAILABLE);
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
    }

    @Test
    void disabledGlobalCreditsStillAllowReadsAndExistingConfirmation() {
        attendance.claim(user);
        doReturn(false).when(creditProperties).enabled();
        doThrow(new BusinessException(CreditErrorCode.UNAVAILABLE)).when(creditProperties).requireEnabled();
        assertThat(attendance.claim(user).alreadyClaimed()).isTrue();
        now = now.plus(Duration.ofDays(1));
        assertThat(attendance.status(user).claimable()).isFalse();
        error(() -> attendance.claim(user), CreditErrorCode.UNAVAILABLE);
        assertThat(count("credit_grants")).isOne();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-02T14:59:59.999999Z, 2026-11-02, 2026-11-02T15:00:00Z",
        "2026-11-02T15:00:00Z, 2026-11-03, 2026-11-03T15:00:00Z",
        "2026-11-30T15:00:00Z, 2026-12-01, 2026-12-01T15:00:00Z",
        "2026-12-31T15:00:00Z, 2027-01-01, 2027-01-01T15:00:00Z"
    })
    void usesKoreanCalendarBoundaries(String instant, String date, String next) {
        now = Instant.parse(instant);
        var status = attendance.status(user);
        assertThat(status.date()).isEqualTo(LocalDate.parse(date));
        assertThat(status.nextClaimAt()).isEqualTo(Instant.parse(next));
        var claim = attendance.claim(user);
        assertThat(claim.date()).isEqualTo(status.date());
        assertThat(claim.nextClaimAt()).isEqualTo(status.nextClaimAt());
    }

    @Test
    void midnightOpensANewRewardAndSkippedDaysAreNotBackfilled() {
        now = Instant.parse("2026-11-02T14:59:59.999999Z");
        attendance.claim(user);
        now = Instant.parse("2026-11-02T15:00:00Z");
        assertThat(attendance.status(user).claimable()).isTrue();
        assertThat(attendance.claim(user).alreadyClaimed()).isFalse();
        now = now.plus(Duration.ofDays(4));
        attendance.claim(user);
        assertThat(credits.balance(user).available()).isEqualTo(3);
        assertThat(count("credit_grants")).isEqualTo(3);
    }

    @Test
    void signupAndAttendanceCanBothBeClaimedOnTheSameDay() {
        user = users.saveAndFlush(User.register(UUID.randomUUID() + "@attendance.test")).getId();
        onboarding.completeOnboarding(user, "출석회원");
        attendance.claim(user);
        assertThat(credits.balance(user).available()).isEqualTo(4);
        assertThat(credits.transactions(user, null, 20).items()).extracting(CreditTransaction::source)
                .containsExactly(CreditSource.ATTENDANCE, CreditSource.SIGNUP);
    }

    @Test
    void missingOrIncompleteUsersCannotReadOrClaim() {
        Long pending = users.saveAndFlush(User.register(UUID.randomUUID() + "@attendance.test")).getId();
        error(() -> attendance.status(pending), UserErrorCode.ONBOARDING_REQUIRED);
        error(() -> attendance.claim(pending), UserErrorCode.ONBOARDING_REQUIRED);
        error(() -> attendance.status(Long.MAX_VALUE), CreditErrorCode.NOT_FOUND);
        error(() -> attendance.claim(Long.MAX_VALUE), CreditErrorCode.NOT_FOUND);
        error(() -> attendance.claim(null), CreditErrorCode.INVALID_INPUT);
        assertNoCreditWrites();
        assertThat(jdbc.queryForObject("select count(*) from credit_grants where user_id=?", Long.class, pending)).isZero();
    }

    @Test
    void concurrentClaimsProduceOneGrantAndOneLedger() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<AttendanceClaim>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("시작 대기 시간 초과");
                return attendance.claim(user);
            }));
            start.countDown();
            var results = new ArrayList<AttendanceClaim>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> !result.alreadyClaimed()).count()).isOne();
            assertThat(results).allSatisfy(result -> assertThat(result.balance().available()).isOne());
        }
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(1, 0, 1));
    }

    @Test
    void dayIsChosenAfterWaitingForTheUserLock() throws Exception {
        now = Instant.parse("2026-11-02T14:59:59Z");
        attendance.claim(user);
        var started = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<AttendanceClaim> future = transaction.execute(status -> {
                users.findByIdForUpdate(user).orElseThrow();
                var pending = pool.submit(() -> {
                    started.countDown();
                    return attendance.claim(user);
                });
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> pending.get(200, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    now = Instant.parse("2026-11-02T15:00:00Z");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return pending;
            });
            var result = future.get(10, TimeUnit.SECONDS);
            assertThat(result.date()).isEqualTo(LocalDate.of(2026, 11, 3));
            assertThat(result.alreadyClaimed()).isFalse();
        }
        assertThat(credits.balance(user).available()).isEqualTo(2);
    }

    @Test
    void concurrentImageReservationAndAttendanceKeepBalancesAndAllocationsConsistent() throws Exception {
        credits.grant(user, CreditSource.PAYMENT, "attendance-race:" + user, 3);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var claim = pool.submit(() -> { start.await(); return attendance.claim(user); });
            var reserve = pool.submit(() -> {
                start.await();
                return reservations.reserve(user, "attendance-race:" + user,
                        CreditUsage.DREAM_IMAGE_GENERATION, "attendance-image:" + user, now.plusSeconds(120));
            });
            start.countDown();
            claim.get(20, TimeUnit.SECONDS);
            var receipt = reserve.get(20, TimeUnit.SECONDS);
            assertThat(credits.balance(user)).isEqualTo(new CreditBalance(1, 3, 4));
            reservations.settle(user, receipt.reservationId(), "attendance-result:" + user);
        }
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(1, 0, 1));
        assertThat(jdbc.queryForObject("select sum(available) from credit_grants where user_id=?", Long.class, user)).isOne();
        assertThat(jdbc.queryForObject("select sum(consumed) from credit_grants where user_id=?", Long.class, user)).isEqualTo(3);
    }

    @Test
    void attendanceCreditsCanBeReservedReleasedAndConsumedWithoutResettingAttendance() {
        for (int i = 0; i < 3; i++) {
            attendance.claim(user);
            if (i < 2) now = now.plus(Duration.ofDays(1));
        }
        var first = reservations.reserve(user, "attendance-first:" + user,
                CreditUsage.DREAM_IMAGE_GENERATION, "attendance-first:" + user, now.plusSeconds(120));
        reservations.release(user, first.reservationId(), CreditReservation.ReleaseReason.FAILURE);
        var second = reservations.reserve(user, "attendance-second:" + user,
                CreditUsage.DREAM_IMAGE_GENERATION, "attendance-second:" + user, now.plusSeconds(120));
        reservations.settle(user, second.reservationId(), "attendance-result:" + user);
        assertThat(credits.balance(user).total()).isZero();
        assertThat(attendance.claim(user).alreadyClaimed()).isTrue();
        assertThat(credits.balance(user).total()).isZero();
        assertThat(jdbc.queryForList("select consumed from credit_grants where user_id=?", Long.class, user))
                .containsExactly(1L, 1L, 1L);
    }

    @Test
    void paymentHoldIsPreservedWhenAttendanceIsGranted() {
        String key = "attendance-hold:" + user;
        credits.grant(user, CreditSource.PAYMENT, key, 3);
        credits.holdPayment(user, key);
        assertThat(attendance.claim(user).balance()).isEqualTo(new CreditBalance(4, 0, 4, true));
        error(() -> reservations.reserve(user, "blocked:" + user, CreditUsage.DREAM_IMAGE_GENERATION,
                "blocked:" + user, now.plusSeconds(120)), CreditErrorCode.PAYMENT_REVIEW);
        assertThat(credits.balance(user).spendingBlocked()).isTrue();
    }

    @Test
    void ledgerFailureRollsBackAccountAndGrantAndAllowsRetry() {
        doThrow(new DataIntegrityViolationException("forced attendance ledger failure")).when(ledger).flush();
        assertThatThrownBy(() -> attendance.claim(user)).isInstanceOf(DataIntegrityViolationException.class);
        assertNoCreditWrites();
        assertThat(attendance.status(user).claimed()).isFalse();
        reset(ledger);
        assertThat(attendance.claim(user).alreadyClaimed()).isFalse();
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
    }

    @Test
    void overflowAndOuterRollbackDoNotConsumeDailyReward() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            attendance.claim(user);
            throw new IllegalStateException("outer rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertNoCreditWrites();
        credits.grant(user, CreditSource.PROMOTION, "attendance-max:" + user, Long.MAX_VALUE);
        error(() -> attendance.claim(user), CreditErrorCode.CAPACITY_EXCEEDED);
        assertThat(attendance.status(user).claimed()).isFalse();
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
    }

    @Test
    void httpRequiresOnboardingAndUsesOnlyAuthenticatedIdentity() throws Exception {
        // JWT의 기본 시간 검증은 실제 시각을 사용하므로 HTTP 테스트에서는 실제 시각으로 발급한다.
        now = Instant.now();
        for (String method : List.of("GET", "POST")) {
            String path = method.equals("GET") ? "/api/v1/attendance" : "/api/v1/attendance/claim";
            assertThat(send(null, method, path).statusCode()).isEqualTo(401);
            Long pending = users.saveAndFlush(User.register(UUID.randomUUID() + "@attendance.test")).getId();
            String pendingToken = tokens.login(pending).response().accessToken();
            assertThat(send(pendingToken, method, path).statusCode()).isEqualTo(403);
        }
        Long other = newUser();
        String token = tokens.login(user).response().accessToken();
        var status = send(token, "GET", "/api/v1/attendance?userId=" + other);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(json.readTree(status.body()).at("/data/claimable").asBoolean()).isTrue();
        assertNoCreditWrites();
        var claim = send(token, "POST", "/api/v1/attendance/claim?userId=" + other + "&amount=100&date=2030-01-01");
        assertThat(claim.statusCode()).isEqualTo(200);
        assertThat(json.readTree(claim.body()).at("/data/balance/available").asLong()).isOne();
        assertThat(json.readTree(claim.body()).at("/data/alreadyClaimed").asBoolean()).isFalse();
        assertThat(credits.balance(other).total()).isZero();
        var retry = send(token, "POST", "/api/v1/attendance/claim");
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(json.readTree(retry.body()).at("/data/alreadyClaimed").asBoolean()).isTrue();
        var history = send(token, "GET", "/api/v1/credits/transactions");
        assertThat(json.readTree(history.body()).at("/data/items/0/source").asString()).isEqualTo("ATTENDANCE");
        for (var response : List.of(status, claim, retry, history)) {
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.body()).doesNotContain("sourceKey", "sourceHash", "attendance:" + user);
        }
        doReturn(false).when(properties).enabled();
        assertThat(send(token, "POST", "/api/v1/attendance/claim").statusCode()).isEqualTo(200);
        var disabled = send(tokens.login(other).response().accessToken(), "POST", "/api/v1/attendance/claim");
        assertThat(disabled.statusCode()).isEqualTo(503);
        assertThat(json.readTree(disabled.body()).at("/code").asString()).isEqualTo("CREDIT_503_1");
    }

    @Test
    void swaggerDocumentsBothResponseSchemasAndNewCreditSource() throws Exception {
        var spec = json.readTree(send(null, "GET", "/v3/api-docs").body());
        var status = spec.at("/paths/~1api~1v1~1attendance/get");
        var claim = spec.at("/paths/~1api~1v1~1attendance~1claim/post");
        assertThat(status.at("/responses/200/content/application~1json/schema/$ref").asString()).contains("AttendanceStatus");
        assertThat(claim.at("/responses/200/content/application~1json/schema/$ref").asString()).contains("AttendanceClaim");
        for (var operation : List.of(status, claim)) {
            assertThat(operation.get("security").size()).isPositive();
            assertThat(operation.get("responses").has("401")).isTrue();
            assertThat(operation.get("responses").has("403")).isTrue();
        }
        assertThat(claim.get("responses").has("503")).isTrue();
        assertThat(spec.get("components").toString()).contains("ATTENDANCE");
    }

    private Long newUser() {
        return users.saveAndFlush(User.create(UUID.randomUUID() + "@attendance.test", "출석회원")).getId();
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id=?", Long.class, user);
    }

    private void assertNoCreditWrites() {
        for (String table : List.of("credit_accounts", "credit_grants", "credit_ledger"))
            assertThat(count(table)).as(table).isZero();
    }

    private static void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private HttpResponse<String> send(String token, String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
