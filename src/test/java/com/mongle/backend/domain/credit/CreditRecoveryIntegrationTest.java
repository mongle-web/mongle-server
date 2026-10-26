package com.mongle.backend.domain.credit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:credit-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.credit.enabled=true","mongle.credit.expiry-policy=NO_EXPIRY","mongle.credit.dream-image-generation=3"})
@ActiveProfiles("test")
class CreditRecoveryIntegrationTest {
    @Autowired CreditService credits;
    @Autowired CreditReservationService reservations;
    @Autowired CreditReservationRecovery recovery;
    @Autowired UserRepository users;
    @Autowired CreditGrantRepository grants;
    @Autowired CreditAllocationRepository allocations;
    @Autowired CreditLedgerRepository ledger;
    @Autowired CreditReservationRepository rows;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name="authClock") Clock clock;
    Instant now=Instant.parse("2030-01-01T00:00:00Z");
    @BeforeEach void clock(){when(clock.instant()).thenAnswer(i->now);when(clock.getZone()).thenReturn(ZoneOffset.UTC);}
    Long funded(){Long u=users.saveAndFlush(User.create(UUID.randomUUID()+"@recovery.test","복구")).getId();credits.grant(u,CreditSource.PAYMENT,"order:"+u,10);return u;}
    CreditReservationReceipt reserve(Long u,String key,Instant deadline){return reservations.reserve(u,key,CreditUsage.DREAM_IMAGE_GENERATION,"image:"+key,deadline);}
    @Test void expiredReservationRejectsLateResultAndRecoveryRestoresBalanceOnlyOnce(){
        Long u=funded();var r=reserve(u,"expired",now.plusSeconds(60));
        assertThat(recovery.recoverExpired(10)).isZero();
        now=now.plusSeconds(60);
        assertThatThrownBy(()->reservations.settle(u,r.reservationId(),"late-version")).isInstanceOf(BusinessException.class);
        assertThat(recovery.recoverExpired(10)).isEqualTo(1);
        assertThat(recovery.recoverExpired(10)).isZero();
        assertThat(credits.balance(u)).isEqualTo(new CreditBalance(10,0,10));
        assertThat(reservations.get(u,r.reservationId()).releaseReason()).isEqualTo(CreditReservation.ReleaseReason.EXPIRED);
        assertThatThrownBy(()->reservations.settle(u,r.reservationId(),"late-version")).isInstanceOf(BusinessException.class);
        assertThat(reserve(u,"expired",now.plusSeconds(100)).state()).isEqualTo(CreditReservation.State.RELEASED);
    }
    @Test void boundedRecoveryKeepsFutureAndSettledReservations(){
        var owners=new ArrayList<Long>();
        for(int i=0;i<3;i++){Long u=funded();owners.add(u);reserve(u,"due-"+u,now.plusSeconds(10));}
        Long future=funded();reserve(future,"future",now.plusSeconds(100));
        Long settled=funded();var success=reserve(settled,"settled",now.plusSeconds(10));reservations.settle(settled,success.reservationId(),"version");
        now=now.plusSeconds(10);
        assertThat(recovery.recoverExpired(2)).isEqualTo(2);
        assertThat(recovery.recoverExpired(2)).isEqualTo(1);
        assertThat(recovery.recoverExpired(2)).isZero();
        for(Long u:owners)assertThat(credits.balance(u)).isEqualTo(new CreditBalance(10,0,10));
        assertThat(credits.balance(future)).isEqualTo(new CreditBalance(7,3,10));
        assertThat(credits.balance(settled)).isEqualTo(new CreditBalance(7,0,7));
        reservations.release(future,rows.findByUserIdAndRequestHash(future,CreditRules.hash("future")).orElseThrow().getId(),CreditReservation.ReleaseReason.CANCELLED);
    }
    @Test void switchingOffNewUsageDoesNotPreventFinishingExistingReservation(){
        Long u=funded();var r=reserve(u,"disable",now.plusSeconds(60));
        var off=new CreditProperties(false,CreditProperties.ExpiryPolicy.UNDECIDED,null,null,null,null,null);
        var disabled=new CreditReservationService(credits,rows,grants,allocations,ledger,off,clock);
        assertThat(transaction.execute(s->disabled.settle(u,r.reservationId(),"saved-version")).state()).isEqualTo(CreditReservation.State.SETTLED);
        assertThat(credits.balance(u)).isEqualTo(new CreditBalance(7,0,7));
    }
    @Test void recoveryRejectsAnExistingTransactionInsteadOfSuspendingItsLocks(){
        assertThatThrownBy(()->transaction.execute(s->recovery.recoverExpired(10)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void allocationPagesBeyondOneHundredLotsAndReleasesAllSources(){
        Long u=users.saveAndFlush(User.create(UUID.randomUUID()+"@many.test","출처")).getId();
        for(int i=0;i<105;i++)credits.grant(u,CreditSource.PROMOTION,"lot:"+u+":"+i,1);
        var policy=new CreditProperties(true,CreditProperties.ExpiryPolicy.NO_EXPIRY,null,103L,null,null,null);
        var service=new CreditReservationService(credits,rows,grants,allocations,ledger,policy,clock);
        var r=transaction.execute(s->service.reserve(u,"many-lots",CreditUsage.DREAM_IMAGE_GENERATION,"many-lots",now.plusSeconds(60)));
        assertThat(credits.balance(u)).isEqualTo(new CreditBalance(2,103,105));
        assertThat(jdbc.queryForObject("select count(*) from credit_allocations where reservation_id=?",Long.class,r.reservationId())).isEqualTo(103);
        reservations.release(u,r.reservationId(),CreditReservation.ReleaseReason.FAILURE);
        assertThat(credits.balance(u)).isEqualTo(new CreditBalance(105,0,105));
    }
}
