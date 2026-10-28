package com.mongle.backend.domain.credit;

import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.*;
import org.junit.jupiter.api.*;
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
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired Clock authClock;
    Long user;
    @BeforeEach void setup(){ user=newUser(); }
    Instant deadline(){return authClock.instant().plusSeconds(60);}
    CreditReservationReceipt reserve(String key){return reservations.reserve(user,key,CreditUsage.DREAM_IMAGE_GENERATION,"attempt:"+key,deadline());}
    @Test void reservesOldestGrantsAndPreservesTheirPurchaseSources() {
        var paid=credits.grant(user,CreditSource.PAYMENT,"paid:"+user,10);
        var free=credits.grant(user,CreditSource.PROMOTION,"free:"+user,10);
        var reservation=reserve("fifo");
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(8,12,20));
        assertThat(grants.findById(paid.grantId()).orElseThrow().getReserved()).isEqualTo(10);
        assertThat(grants.findById(free.grantId()).orElseThrow().getReserved()).isEqualTo(2);
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
