package com.mongle.backend.domain.credit;

import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:credit-reservations;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.credit.enabled=true", "mongle.credit.expiry-policy=NO_EXPIRY",
    "mongle.credit.dream-image-generation=12", "mongle.credit.dream-image-regeneration=7",
    "mongle.credit.world-image-generation="})
@ActiveProfiles("test")
class CreditReservationIntegrationTest {
    @Autowired CreditService credits;
    @Autowired CreditReservationService reservations;
    @Autowired CreditGrantRepository grants;
    @Autowired CreditReservationRepository rows;
    @Autowired CreditAllocationRepository allocations;
    @Autowired CreditLedgerRepository ledger;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired Clock authClock;
    Long user;
    @BeforeEach void setup(){ user=newUser(); }
    Instant deadline(){return authClock.instant().plusSeconds(60);}
    CreditReservationReceipt reserve(String key){return reservations.reserve(user,key,CreditUsage.DREAM_IMAGE_GENERATION,"attempt:"+key,deadline());}
    @Test void reservesFreeGrantsBeforeOlderPurchasesAndPreservesTheirSources() {
        var paid=credits.grant(user,CreditSource.PAYMENT,"paid:"+user,10);
        var free=credits.grant(user,CreditSource.PROMOTION,"free:"+user,10);
        var reservation=reserve("fifo");
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(8,12,20));
        assertThat(grants.findById(paid.grantId()).orElseThrow().getReserved()).isEqualTo(2);
        assertThat(grants.findById(free.grantId()).orElseThrow().getReserved()).isEqualTo(10);
        assertThat(jdbc.queryForList("select grant_id from credit_allocations where reservation_id=? order by id",Long.class,reservation.reservationId())).containsExactly(free.grantId(),paid.grantId());
        assertThat(jdbc.queryForList("select amount from credit_allocations where reservation_id=? order by id",Long.class,reservation.reservationId())).containsExactly(10L,2L);
        assertThat(credits.transactions(user,null,1).items().getFirst().kind()).isEqualTo(CreditLedger.Kind.RESERVE);
    }
    @Test void duplicateReservationKeepsOriginalAmountAndDeadline() {
        credits.grant(user,CreditSource.PAYMENT,"duplicate:"+user,20);
        var a=reserve("same");
        var b=reservations.reserve(user,"same",CreditUsage.DREAM_IMAGE_GENERATION,"attempt:same",deadline().plusSeconds(100));
        assertThat(b).isEqualTo(a);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(8,12,20));
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id=? and kind='RESERVE'",Long.class,user)).isEqualTo(1);
    }
    @Test void preventsAnotherRequestFromReservingSameAttemptAndHidesOtherUsersReservations() {
        credits.grant(user,CreditSource.PROMOTION,"conflict:"+user,100);
        var r=reserve("target");
        error(()->reservations.reserve(user,"another-key",CreditUsage.DREAM_IMAGE_GENERATION,"attempt:target",deadline()),CreditErrorCode.REQUEST_CONFLICT);
        error(()->reservations.reserve(user,"target",CreditUsage.DREAM_IMAGE_REGENERATION,"attempt:target",deadline()),CreditErrorCode.REQUEST_CONFLICT);
        error(()->reservations.get(newUser(),r.reservationId()),CreditErrorCode.NOT_FOUND);
    }
    @Test void insufficientBalanceUnconfiguredUsageAndInvalidLeaseDoNotReserve() {
        credits.grant(user,CreditSource.PROMOTION,"small:"+user,11);
        error(()->reserve("insufficient"),CreditErrorCode.INSUFFICIENT);
        error(()->reservations.reserve(user,"world",CreditUsage.WORLD_IMAGE_GENERATION,"world:1",deadline()),CreditErrorCode.UNAVAILABLE);
        error(()->reservations.reserve(user,"expired",CreditUsage.DREAM_IMAGE_GENERATION,"image:1",authClock.instant().minusSeconds(1)),CreditErrorCode.INVALID_INPUT);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(11,0,11));
    }
    @Test void reservationAndGrantAllocationRollBackWithAttemptCreation() {
        credits.grant(user,CreditSource.PROMOTION,"rollback:"+user,20);
        assertThatThrownBy(()->transaction.execute(s->{reserve("rollback");throw new IllegalStateException("시도 저장 실패");})).isInstanceOf(IllegalStateException.class);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(20,0,20));
        assertThat(jdbc.queryForObject("select count(*) from credit_reservations where user_id=?",Long.class,user)).isZero();
        assertThat(reserve("rollback").amount()).isEqualTo(12);
    }
    @Test void concurrentReservationsCannotSpendTheSameBalance() throws Exception {
        credits.grant(user,CreditSource.PAYMENT,"race-reserve:"+user,12);
        assertThat(race(()->reserve("race-a"),()->reserve("race-b"))).containsExactlyInAnyOrder("OK",CreditErrorCode.INSUFFICIENT.getCode());
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(0,12,12));
    }
    @Test void concurrentDuplicateGrantAndReservationEachApplyOnce() throws Exception {
        assertThat(race(()->credits.grant(user,CreditSource.PAYMENT,"race-grant:"+user,24),()->credits.grant(user,CreditSource.PAYMENT,"race-grant:"+user,24))).containsOnly("OK");
        assertThat(race(()->reserve("race-same"),()->reserve("race-same"))).containsOnly("OK");
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(12,12,24));
    }
    @Test void settlementConsumesReservedCreditsExactlyOnceAndConflictingResultIsRejected() {
        var g=credits.grant(user,CreditSource.PAYMENT,"settle:"+user,20);
        var r=reserve("settle");
        var success=reservations.settle(user,r.reservationId(),"image-version:1");
        assertThat(success.state()).isEqualTo(CreditReservation.State.SETTLED);
        assertThat(reservations.settle(user,r.reservationId(),"image-version:1")).isEqualTo(success);
        error(()->reservations.settle(user,r.reservationId(),"image-version:2"),CreditErrorCode.REQUEST_CONFLICT);
        error(()->reservations.release(user,r.reservationId(),CreditReservation.ReleaseReason.FAILURE),CreditErrorCode.STATE_CONFLICT);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(8,0,8));
        assertThat(grants.findById(g.grantId()).orElseThrow().getConsumed()).isEqualTo(12);
        assertThat(credits.transactions(user,null,1).items().getFirst().reservedDelta()).isEqualTo(-12);
    }
    @Test void failureReleaseRestoresSameSourcesAndPreservesFirstReason() {
        var g=credits.grant(user,CreditSource.PAYMENT,"release:"+user,20);
        var r=reserve("release");
        var released=reservations.release(user,r.reservationId(),CreditReservation.ReleaseReason.FAILURE);
        assertThat(reservations.release(user,r.reservationId(),CreditReservation.ReleaseReason.CANCELLED)).isEqualTo(released);
        error(()->reservations.settle(user,r.reservationId(),"late-result"),CreditErrorCode.STATE_CONFLICT);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(20,0,20));
        var source=grants.findById(g.grantId()).orElseThrow();
        assertThat(source.getAvailable()).isEqualTo(20);assertThat(source.getConsumed()).isZero();assertThat(source.getReserved()).isZero();
        assertThat(reserve("release")).isEqualTo(released);
    }
    @Test void settlementAndReleaseRollBackWithResultTransaction() {
        credits.grant(user,CreditSource.PAYMENT,"result-rollback:"+user,20);
        var r=reserve("result-rollback");
        assertThatThrownBy(()->transaction.execute(s->{reservations.settle(user,r.reservationId(),"image:1");throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(8,12,20));
        assertThat(reservations.get(user,r.reservationId()).state()).isEqualTo(CreditReservation.State.RESERVED);
        assertThatThrownBy(()->transaction.execute(s->{reservations.release(user,r.reservationId(),CreditReservation.ReleaseReason.FAILURE);throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(8,12,20));
    }
    @Test void concurrentSettlementAndFailureCannotBothWin() throws Exception {
        credits.grant(user,CreditSource.PAYMENT,"completion-race:"+user,20);
        var r=reserve("completion-race");
        assertThat(race(()->reservations.settle(user,r.reservationId(),"image:1"),
                ()->reservations.release(user,r.reservationId(),CreditReservation.ReleaseReason.FAILURE)))
                .containsExactlyInAnyOrder("OK",CreditErrorCode.STATE_CONFLICT.getCode());
        var state=reservations.get(user,r.reservationId()).state();
        assertThat(credits.balance(user)).isEqualTo(state==CreditReservation.State.SETTLED
                ?new CreditBalance(8,0,8):new CreditBalance(20,0,20));
    }
    @Test void cannotSettleOrReleaseAnotherUsersReservation() {
        credits.grant(user,CreditSource.PAYMENT,"ownership:"+user,20);var r=reserve("ownership");Long other=newUser();
        error(()->reservations.settle(other,r.reservationId(),"image:1"),CreditErrorCode.NOT_FOUND);
        error(()->reservations.release(other,r.reservationId(),CreditReservation.ReleaseReason.CANCELLED),CreditErrorCode.NOT_FOUND);
        error(()->reservations.release(user,r.reservationId(),CreditReservation.ReleaseReason.EXPIRED),CreditErrorCode.STATE_CONFLICT);
    }
    @Test void staleOuterTransactionCannotOverwriteNewlyGrantedBalance() throws Exception {
        credits.grant(user,CreditSource.PAYMENT,"stale-initial:"+user,20);
        assertThatThrownBy(()->transaction.execute(s->{
            credits.balance(user);
            try(var pool=Executors.newSingleThreadExecutor()) {
                pool.submit(()->credits.grant(user,CreditSource.PAYMENT,"stale-new:"+user,10)).get(10,TimeUnit.SECONDS);
            } catch(Exception e) { throw new AssertionError(e); }
            reserve("stale-attempt");
            return null;
        })).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(30,0,30));
        assertThat(jdbc.queryForObject("select count(*) from credit_reservations where user_id=?",Long.class,user)).isZero();
    }

    @Test
    void freeBalanceCoversTheWholeCostWithoutReservingAnOlderPurchase() {
        var paid = credits.grant(user, CreditSource.PAYMENT, "whole-paid:" + user, 10);
        var free = credits.grant(user, CreditSource.SIGNUP, "whole-free:" + user, 12);

        var reservation = reserve("whole-free");

        assertThat(allocationIds(reservation)).containsExactly(free.grantId());
        assertGrant(paid.grantId(), 10, 0, 0);
        assertThat(credits.balanceBreakdown(user)).isEqualTo(
                new CreditBalanceResponse(10, 12, 22, false, 10, 0, 0, 12));
    }

    @Test
    void freeKindsShareGrantOrderRatherThanTakingPriorityByKind() {
        var paid = credits.grant(user, CreditSource.PAYMENT, "kind-paid:" + user, 20);
        var promotion = credits.grant(user, CreditSource.PROMOTION, "kind-promo:" + user, 2);
        var attendance = credits.grant(user, CreditSource.ATTENDANCE, "kind-attend:" + user, 3);
        var signup = credits.grant(user, CreditSource.SIGNUP, "kind-signup:" + user, 4);
        var latest = credits.grant(user, CreditSource.PROMOTION, "kind-latest:" + user, 5);

        var reservation = reserve("free-kinds");

        assertThat(allocationIds(reservation)).containsExactly(
                promotion.grantId(), attendance.grantId(), signup.grantId(), latest.grantId());
        assertThat(allocationAmounts(reservation)).containsExactly(2L, 3L, 4L, 3L);
        assertGrant(paid.grantId(), 20, 0, 0);
        assertGrant(latest.grantId(), 2, 3, 0);
    }

    @Test
    void paidRemainderUsesOlderPurchasesFirst() {
        var older = credits.grant(user, CreditSource.PAYMENT, "paid-first:" + user, 5);
        var free = credits.grant(user, CreditSource.ATTENDANCE, "paid-free:" + user, 4);
        var newer = credits.grant(user, CreditSource.PAYMENT, "paid-last:" + user, 10);

        var reservation = reserve("paid-order");

        assertThat(allocationIds(reservation)).containsExactly(free.grantId(), older.grantId(), newer.grantId());
        assertThat(allocationAmounts(reservation)).containsExactly(4L, 5L, 3L);
        assertThat(credits.balanceBreakdown(user)).isEqualTo(
                new CreditBalanceResponse(7, 12, 19, false, 7, 8, 0, 4));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SETTLE", "FAILURE", "CANCELLED", "EXPIRED"})
    void mixedReservationFinishesUsingItsOriginalSources(String outcome) {
        var paid = credits.grant(user, CreditSource.PAYMENT, "finish-paid:" + user, 10);
        var free = credits.grant(user, CreditSource.PROMOTION, "finish-free:" + user, 5);
        var reservation = reserve("mixed-finish");
        var ids = allocationIds(reservation);
        var amounts = allocationAmounts(reservation);
        var later = credits.grant(user, CreditSource.ATTENDANCE, "finish-later:" + user, 20);

        if (outcome.equals("SETTLE")) {
            var settled = reservations.settle(user, reservation.reservationId(), "mixed-result");
            assertThat(reservations.settle(user, reservation.reservationId(), "mixed-result")).isEqualTo(settled);
            assertGrant(paid.grantId(), 3, 0, 7);
            assertGrant(free.grantId(), 0, 0, 5);
            assertThat(credits.balanceBreakdown(user)).isEqualTo(
                    new CreditBalanceResponse(23, 0, 23, false, 3, 0, 20, 0));
        } else {
            var reason = CreditReservation.ReleaseReason.valueOf(outcome);
            if (reason == CreditReservation.ReleaseReason.EXPIRED) {
                jdbc.update("update credit_reservations set expires_at=? where id=?",
                        LocalDateTime.ofInstant(authClock.instant().minusSeconds(1), ZoneOffset.UTC)
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                        reservation.reservationId());
                assertThat(reservations.expire(user, reservation.reservationId())).isTrue();
                assertThat(reservations.expire(user, reservation.reservationId())).isFalse();
            } else {
                reservations.release(user, reservation.reservationId(), reason);
            }
            var released = reservations.get(user, reservation.reservationId());
            assertThat(reservations.release(user, reservation.reservationId(), reason)).isEqualTo(released);
            assertThat(released.releaseReason()).isEqualTo(reason);
            assertGrant(paid.grantId(), 10, 0, 0);
            assertGrant(free.grantId(), 5, 0, 0);
            assertThat(credits.balanceBreakdown(user)).isEqualTo(
                    new CreditBalanceResponse(35, 0, 35, false, 10, 0, 25, 0));
        }
        assertGrant(later.grantId(), 20, 0, 0);
        assertThat(allocationIds(reservation)).isEqualTo(ids);
        assertThat(allocationAmounts(reservation)).isEqualTo(amounts);
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where reservation_id=? and kind in ('SETTLE','RELEASE')",
                Long.class, reservation.reservationId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void freePrioritySurvivesAllocationPagesAndKeepsAllSources(boolean settle) {
        var olderPaid = credits.grant(user, CreditSource.PAYMENT, "page-old:" + user, 5);
        var freeIds = new ArrayList<Long>();
        var kinds = List.of(CreditSource.SIGNUP, CreditSource.PROMOTION, CreditSource.ATTENDANCE);
        for (int i = 0; i < 103; i++) {
            freeIds.add(credits.grant(user, kinds.get(i % kinds.size()), "page-free:" + user + ":" + i, 1).grantId());
        }
        var newerPaid = credits.grant(user, CreditSource.PAYMENT, "page-new:" + user, 20);
        var policy = new CreditProperties(true, CreditProperties.ExpiryPolicy.NO_EXPIRY, 3L, 115L, null, null, null);
        var service = new CreditReservationService(credits, rows, grants, allocations, ledger, policy, authClock);
        var reservation = transaction.execute(s -> service.reserve(user, "multi-page", CreditUsage.DREAM_IMAGE_GENERATION,
                "multi-page", authClock.instant().plusSeconds(300)));
        var expectedIds = new ArrayList<>(freeIds);
        expectedIds.add(olderPaid.grantId());
        expectedIds.add(newerPaid.grantId());
        assertThat(allocationIds(reservation)).containsExactlyElementsOf(expectedIds);
        assertThat(allocationAmounts(reservation)).hasSize(105).endsWith(5L, 7L);

        if (settle) {
            reservations.settle(user, reservation.reservationId(), "page-result");
            for (var id : freeIds) assertGrant(id, 0, 0, 1);
            assertGrant(olderPaid.grantId(), 0, 0, 5);
            assertGrant(newerPaid.grantId(), 13, 0, 7);
            assertThat(credits.balance(user)).isEqualTo(new CreditBalance(13, 0, 13));
        } else {
            reservations.release(user, reservation.reservationId(), CreditReservation.ReleaseReason.FAILURE);
            for (var id : freeIds) assertGrant(id, 1, 0, 0);
            assertGrant(olderPaid.grantId(), 5, 0, 0);
            assertGrant(newerPaid.grantId(), 20, 0, 0);
            assertThat(credits.balance(user)).isEqualTo(new CreditBalance(128, 0, 128));
        }
        assertThat(allocationIds(reservation)).containsExactlyElementsOf(expectedIds);
    }

    @Test
    void duplicateRequestDoesNotRedistributeAnExistingReservationAfterNewFreeCredit() {
        var paid = credits.grant(user, CreditSource.PAYMENT, "duplicate-paid:" + user, 20);
        var free = credits.grant(user, CreditSource.SIGNUP, "duplicate-free:" + user, 3);
        var reservation = reserve("duplicate-sources");
        var later = credits.grant(user, CreditSource.PROMOTION, "duplicate-later:" + user, 20);
        var history = jdbc.queryForList("select * from credit_ledger where user_id=? order by id", user);

        assertThat(reservations.reserve(user, "duplicate-sources", CreditUsage.DREAM_IMAGE_GENERATION,
                "attempt:duplicate-sources", deadline().plusSeconds(100))).isEqualTo(reservation);

        assertThat(allocationIds(reservation)).containsExactly(free.grantId(), paid.grantId());
        assertThat(allocationAmounts(reservation)).containsExactly(3L, 9L);
        assertGrant(later.grantId(), 20, 0, 0);
        assertThat(jdbc.queryForList("select * from credit_ledger where user_id=? order by id", user)).isEqualTo(history);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preexistingPaidFirstReservationIsNotReallocated(boolean settle) {
        var paid = credits.grant(user, CreditSource.PAYMENT, "legacy-paid:" + user, 10);
        var free = credits.grant(user, CreditSource.PROMOTION, "legacy-free:" + user, 10);
        // Persist the old paid-first allocation, as it could exist when this version is deployed.
        var reservation = transaction.execute(s -> {
            credits.requireUser(user, true);
            var account = credits.account(user);
            account.reserve(12);
            var row = rows.saveAndFlush(CreditReservation.create(user, "legacy", CreditUsage.DREAM_IMAGE_GENERATION,
                    "attempt:legacy", 12, deadline()));
            var paidGrant = grants.findById(paid.grantId()).orElseThrow();
            var freeGrant = grants.findById(free.grantId()).orElseThrow();
            paidGrant.reserve(10);
            freeGrant.reserve(2);
            allocations.save(CreditAllocation.create(row, paidGrant, 10));
            allocations.save(CreditAllocation.create(row, freeGrant, 2));
            ledger.saveAndFlush(CreditLedger.entry(account, CreditLedger.Kind.RESERVE, null, row.getId(),
                    null, row.getUsage(), -12, 12));
            return CreditReservationReceipt.from(row);
        });
        var history = jdbc.queryForList("select * from credit_ledger where user_id=? order by id", user);
        assertThat(reserve("legacy")).isEqualTo(reservation);
        assertThat(jdbc.queryForList("select * from credit_ledger where user_id=? order by id", user)).isEqualTo(history);

        if (settle) {
            reservations.settle(user, reservation.reservationId(), "legacy-result");
            assertGrant(paid.grantId(), 0, 0, 10);
            assertGrant(free.grantId(), 8, 0, 2);
        } else {
            reservations.release(user, reservation.reservationId(), CreditReservation.ReleaseReason.FAILURE);
            assertGrant(paid.grantId(), 10, 0, 0);
            assertGrant(free.grantId(), 10, 0, 0);
        }
        assertThat(allocationIds(reservation)).containsExactly(paid.grantId(), free.grantId());
        assertThat(allocationAmounts(reservation)).containsExactly(10L, 2L);
    }

    @Test
    void concurrentMixedReservationsApplyEachSourceOnlyOnce() throws Exception {
        var paid = credits.grant(user, CreditSource.PAYMENT, "race-mixed-paid:" + user, 12);
        var free = credits.grant(user, CreditSource.SIGNUP, "race-mixed-free:" + user, 12);

        assertThat(race(() -> reserve("mixed-race-a"), () -> reserve("mixed-race-b"))).containsOnly("OK");

        assertGrant(paid.grantId(), 0, 12, 0);
        assertGrant(free.grantId(), 0, 12, 0);
        var first = rows.findByUserIdAndRequestHash(user, CreditRules.hash("mixed-race-a")).orElseThrow();
        var second = rows.findByUserIdAndRequestHash(user, CreditRules.hash("mixed-race-b")).orElseThrow();
        assertThat(List.of(allocationIds(CreditReservationReceipt.from(first)), allocationIds(CreditReservationReceipt.from(second))))
                .containsExactlyInAnyOrder(List.of(free.grantId()), List.of(paid.grantId()));
        assertThat(credits.balanceBreakdown(user)).isEqualTo(
                new CreditBalanceResponse(0, 24, 24, false, 0, 12, 0, 12));
    }

    @Test
    void mixedReservationRollbackRestoresBothSourcesAndLeavesNoAllocation() {
        var paid = credits.grant(user, CreditSource.PAYMENT, "mixed-rollback-paid:" + user, 10);
        var free = credits.grant(user, CreditSource.ATTENDANCE, "mixed-rollback-free:" + user, 5);
        var history = jdbc.queryForList("select * from credit_ledger where user_id=? order by id", user);

        assertThatThrownBy(() -> transaction.execute(s -> {
            reserve("mixed-rollback");
            throw new IllegalStateException("시도 저장 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertGrant(paid.grantId(), 10, 0, 0);
        assertGrant(free.grantId(), 5, 0, 0);
        assertThat(jdbc.queryForObject("select count(*) from credit_allocations a join credit_reservations r on r.id=a.reservation_id where r.user_id=?",
                Long.class, user)).isZero();
        assertThat(jdbc.queryForList("select * from credit_ledger where user_id=? order by id", user)).isEqualTo(history);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(15, 0, 15));
    }

    @Test
    void insufficientMixedBalanceAndPaymentHoldDoNotAllocateEitherSource() {
        var paid = credits.grant(user, CreditSource.PAYMENT, "reject-paid:" + user, 8);
        var free = credits.grant(user, CreditSource.SIGNUP, "reject-free:" + user, 3);
        error(() -> reserve("reject-insufficient"), CreditErrorCode.INSUFFICIENT);
        credits.grant(user, CreditSource.ATTENDANCE, "reject-extra:" + user, 10);
        credits.holdPayment(user, "reject-paid:" + user);
        error(() -> reserve("reject-hold"), CreditErrorCode.PAYMENT_REVIEW);

        assertGrant(paid.grantId(), 8, 0, 0);
        assertGrant(free.grantId(), 3, 0, 0);
        assertThat(credits.balanceBreakdown(user)).isEqualTo(
                new CreditBalanceResponse(21, 0, 21, true, 8, 0, 13, 0));
        assertThat(jdbc.queryForObject("select count(*) from credit_reservations where user_id=?", Long.class, user)).isZero();
    }

    List<Long> allocationIds(CreditReservationReceipt reservation) {
        return jdbc.queryForList("select grant_id from credit_allocations where reservation_id=? order by id",
                Long.class, reservation.reservationId());
    }

    List<Long> allocationAmounts(CreditReservationReceipt reservation) {
        return jdbc.queryForList("select amount from credit_allocations where reservation_id=? order by id",
                Long.class, reservation.reservationId());
    }

    void assertGrant(Long id, long available, long reserved, long consumed) {
        var grant = grants.findById(id).orElseThrow();
        assertThat(grant.getAvailable()).as("grant %s available", id).isEqualTo(available);
        assertThat(grant.getReserved()).as("grant %s reserved", id).isEqualTo(reserved);
        assertThat(grant.getConsumed()).as("grant %s consumed", id).isEqualTo(consumed);
    }
    Long newUser(){return users.saveAndFlush(User.create(UUID.randomUUID()+"@reserve.test","예약")).getId();}
    void error(Runnable r,ErrorCode e){assertThatThrownBy(r::run).isInstanceOfSatisfying(BusinessException.class,x->assertThat(x.getErrorCode()).isEqualTo(e));}
    List<String> race(Runnable a,Runnable b) throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)){
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            var futures=new ArrayList<Future<String>>();
            for(var r:List.of(a,b))futures.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError();
                try{r.run();return "OK";}catch(BusinessException e){return e.getErrorCode().getCode();}}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(futures.getFirst().get(15,TimeUnit.SECONDS),futures.getLast().get(15,TimeUnit.SECONDS));
        }
    }
}
