package com.mongle.backend.domain.user;

import com.mongle.backend.domain.auth.entity.*;
import com.mongle.backend.domain.auth.oauth.SocialIdentity;
import com.mongle.backend.domain.auth.repository.*;
import com.mongle.backend.domain.auth.service.*;
import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.service.UserService;
import com.mongle.backend.domain.user.withdrawal.*;
import com.mongle.backend.global.error.BusinessException;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:withdrawal;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "mongle.withdrawal.enabled=true","mongle.withdrawal.worker-enabled=false",
        "mongle.withdrawal.identity-key=withdrawal-test-identity-key-at-least-32-characters",
        "mongle.withdrawal.encryption-key=withdrawal-test-encryption-key-at-least-32-characters",
        "mongle.credit.enabled=true","mongle.credit.dream-image-generation=2"})
@ActiveProfiles("test")
public class UserWithdrawalIntegrationTest {
    @Autowired UserRepository users;
    @Autowired UserService userService;
    @Autowired WithdrawalService withdrawals;
    @Autowired SocialLoginService login;
    @Autowired SocialAccountRepository accounts;
    @Autowired TokenService tokens;
    @Autowired CreditService credits;
    @Autowired CreditReservationService reservations;
    @Autowired SocialUnlinkTaskRepository tasks;
    @Autowired SignupRewardHistoryRepository history;
    @Autowired WithdrawalSecrets secrets;
    @Autowired UnlinkTransactions unlink;
    @Autowired WithdrawalRetention retention;
    @Autowired ImageCleanupTransactions discarded;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean WithdrawalRefundPolicy refunds;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean WithdrawalProperties withdrawalProperties;
    @Value("${local.server.port}") int port;
    static final AtomicLong IDS=new AtomicLong(100000);
    final JsonMapper json=JsonMapper.builder().build();

    @BeforeAll static void nativeOnlyTables(@Autowired JdbcTemplate jdbc) {
        Boolean exists=jdbc.execute((ConnectionCallback<Boolean>) c->{
            try(var tables=c.getMetaData().getTables(c.getCatalog(),null,"payment_credit_reclamations",null)) {
                return tables.next();
            }
        });
        if(!Boolean.TRUE.equals(exists))
            jdbc.execute((ConnectionCallback<Void>) c->{
                new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                        new org.springframework.core.io.ClassPathResource("db/migration/V6__payment_credit_reclamations.sql"))
                        .populate(c);
                return null;
            });
    }

    SocialIdentity identity() { return new SocialIdentity(SocialProvider.KAKAO,""+IDS.incrementAndGet(),UUID.randomUUID()+"@withdraw.test"); }
    Long registered(SocialIdentity identity) { return login.login(identity).userId(); }
    Long member() {
        Long user=registered(identity());
        userService.completeOnboarding(user,"몽글이"); return user;
    }
    void withdraw(Long user) { withdrawals.withdraw(user); }

    @Test
    void disabledFeatureReturns503WithoutChangingUser() throws Exception {
        Long user=member(); var token=tokens.login(user);
        doReturn(false).when(withdrawalProperties).enabled();
        try(var client=HttpClient.newHttpClient()) {
            var response=send(client,token.response().accessToken(),"DELETE","/api/v1/users/me");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("USER_503_1");
        }
        assertThat(users.findById(user).orElseThrow().isWithdrawn()).isFalse();
    }

    @Test
    void openApiExposesDeleteAndPaidFreeBreakdown() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var document=json.readTree(send(client,null,"GET","/v3/api-docs").body());
            var operation=document.at("/paths/~1api~1v1~1users~1me/delete");
            assertThat(operation.get("summary").asString()).isEqualTo("회원탈퇴");
            assertThat(operation.at("/security/0/bearerAuth").isArray()).isTrue();
            assertThat(operation.get("responses").has("409")).isTrue();
            assertThat(document.at("/components/schemas/CreditBalanceResponse/properties").has("paidReserved")).isTrue();
        }
    }

    @Test
    void httpWithdrawalRevokesAllDevicesAndAllowsPendingOnboarding() throws Exception {
        Long user=registered(identity());
        var first=tokens.login(user); var second=tokens.login(user);
        try(var client=HttpClient.newHttpClient()) {
            assertThat(send(client,null,"DELETE","/api/v1/users/me").statusCode()).isEqualTo(401);
            var response=send(client,first.response().accessToken(),"DELETE","/api/v1/users/me");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(response.headers().allValues("Set-Cookie").toString()).contains("MONGLE_REFRESH=","Max-Age=0");
            for(var token:List.of(first,second))
                assertThat(send(client,token.response().accessToken(),"GET","/api/v1/users/me").statusCode()).isEqualTo(401);
        }
        assertThatThrownBy(()->tokens.refresh(second.refreshToken())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->tokens.login(user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->userService.completeOnboarding(user,"다른이름")).isInstanceOf(BusinessException.class);
        assertThat(users.findById(user).orElseThrow().getEmail()).isEqualTo("deleted@withdrawn.invalid");
        assertThat(accounts.findByUserId(user)).isEmpty();
    }

    @Test
    void deletesFullGraphAndRegistersCurrentAndInFlightImagesWithoutTouchingOthers() {
        Long user=member(),other=member();
        graph(user,LocalDate.of(2026,10,1)); graph(other,LocalDate.of(2026,10,1));
        withdraw(user);
        for(String table:List.of("dreams","dream_analyses","dream_stories","dream_images","dream_scenes","dream_entities",
                "world_folders","world_compositions","world_composition_entries","world_summaries","world_summary_sources",
                "world_images","world_image_sources","world_bridge_results","recent_interpretations","dream_generation_jobs",
                "ai_generation_logs","credit_grants","credit_accounts","social_accounts")) {
            assertThat(count(table,user)).as(table).isZero();
            assertThat(count(table,other)).as(table).isPositive();
        }
        assertThat(count("image_cleanup_requests",user)).isEqualTo(6);
        assertThat(users.findById(other).orElseThrow().isWithdrawn()).isFalse();
        // Late upload after deletion requeues the exact cleanup key, without resurrecting image/content.
        String key=jdbc.queryForObject("select min(storage_key) from image_cleanup_requests where user_id=?",String.class,user);
        jdbc.update("update image_cleanup_requests set status='COMPLETED' where storage_key=?",key);
        Long image=jdbc.queryForObject("select image_id from image_cleanup_requests where storage_key=?",Long.class,key);
        discarded.recordDiscardedUpload(user,image,key);
        assertThat(jdbc.queryForObject("select status from image_cleanup_requests where storage_key=?",String.class,key)).isEqualTo("PENDING");
    }

    @Test
    void paidBreakdownAndDefaultRefundGateLeaveEverythingUnchanged() throws Exception {
        Long user=member();
        credits.grant(user,CreditSource.PAYMENT,"purchase:"+user,10);
        var r=reservations.reserve(user,"reserved:"+user,CreditUsage.DREAM_IMAGE_GENERATION,"target:"+user,Instant.now().plusSeconds(600));
        var breakdown=credits.balanceBreakdown(user);
        assertThat(breakdown.freeAvailable()).isEqualTo(1);
        assertThat(breakdown.freeReserved()).isEqualTo(2);
        assertThat(breakdown.paidAvailable()).isEqualTo(10);
        var before=jdbc.queryForMap("select * from users where id=?",user);
        var token=tokens.login(user);
        try(var client=HttpClient.newHttpClient()) {
            var response=send(client,token.response().accessToken(),"DELETE","/api/v1/users/me");
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).contains("USER_409_2");
            assertThat(send(client,token.response().accessToken(),"GET","/api/v1/credits/balance").body())
                    .contains("\"paidAvailable\":10","\"freeReserved\":2");
        }
        assertThat(jdbc.queryForMap("select * from users where id=?",user)).isEqualTo(before);
        assertThat(reservations.get(user,r.reservationId()).state()).isEqualTo(CreditReservation.State.RESERVED);
        assertThat(count("social_accounts",user)).isEqualTo(1);
    }

    @Test
    void acceptedRefundPreservesFinancialRowsCancelsReservationsAndForfeitsFreeBalance() {
        Long user=member();
        credits.grant(user,CreditSource.PAYMENT,"purchase:"+user,10);
        seed("payment_orders",Map.of("user_id",user,"price",1000L,"credits",10L,"state","PAID"));
        reservations.reserve(user,"reserved:"+user,CreditUsage.DREAM_IMAGE_GENERATION,"target:"+user,Instant.now().plusSeconds(600));
        when(refunds.coversRemainingPurchases(user)).thenReturn(true);
        withdraw(user);
        assertThat(count("payment_orders",user)).isEqualTo(1);
        assertThat(count("credit_grants",user)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select available from credit_accounts where user_id=?",Long.class,user)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select reserved from credit_accounts where user_id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("select sum(forfeited) from credit_grants where user_id=?",Long.class,user)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from credit_reservations where user_id=? and state='RESERVED'",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("select sum(available_delta) from credit_ledger where user_id=?",Long.class,user)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select sum(reserved_delta) from credit_ledger where user_id=?",Long.class,user)).isZero();
        // New validated payment bookkeeping is allowed, ordinary spending and user reads are not.
        credits.grant(user,CreditSource.PAYMENT,"late-purchase:"+user,5);
        assertThatThrownBy(()->credits.grant(user,CreditSource.ATTENDANCE,"late-free:"+user,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->credits.balance(user)).isInstanceOf(BusinessException.class);
    }

    @Test
    void unconfirmedPaymentBlocksButUnusedCheckoutLinkDoesNot() {
        Long blocked=member(); seed("payment_orders",Map.of("user_id",blocked,"price",1000L,"credits",10L,"state","UNKNOWN"));
        assertThatThrownBy(()->withdraw(blocked)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").extracting("code").isEqualTo("USER_409_3");
        Long allowed=member(); seed("payment_orders",Map.of("user_id",allowed,"price",1000L,"credits",10L,"state","READY"));
        withdraw(allowed);
        assertThat(users.findById(allowed).orElseThrow().isWithdrawn()).isTrue();
    }

    @Test
    void rejoinCancelsUnstartedUnlinkAndNeverReceivesSignupRewardAgain() {
        var id=identity(); Long first=registered(id);
        userService.completeOnboarding(first,"최초회원");
        withdraw(first);
        Long rejoined=registered(id);
        assertThat(rejoined).isNotEqualTo(first);
        assertThat(userService.completeOnboarding(rejoined,"재가입자").onboardingCompleted()).isTrue();
        assertThat(credits.balance(rejoined).total()).isZero();
        var task=tasks.findById(secrets.identity("KAKAO",id.providerUserId())).orElseThrow();
        assertThat(task.getState()).isEqualTo(SocialUnlinkTask.State.CANCELLED);
        assertThat(task.getEncryptedProviderId()).isNull();
    }

    @Test
    void inFlightUnlinkBlocksNewSessionUntilDefiniteCompletionAndScrubsProviderId() {
        var id=identity(); Long first=registered(id); withdraw(first);
        String hash=secrets.identity("KAKAO",id.providerUserId());
        var claim=unlink.claim(hash).orElseThrow();
        assertThatThrownBy(()->registered(id)).isInstanceOf(BusinessException.class);
        unlink.finish(claim,true);
        assertThat(tasks.findById(hash).orElseThrow().getEncryptedProviderId()).isNull();
        Long rejoined=registered(id);
        unlink.finish(claim,false); // stale callback cannot restart completed/cancelled work
        assertThat(users.findById(rejoined).orElseThrow().isWithdrawn()).isFalse();
    }

    @Test
    void disablingNewWithdrawalsDoesNotResetSignupBenefitHistory() {
        var id=identity(); Long first=registered(id); userService.completeOnboarding(first,"최초회원");
        withdraw(first);
        doReturn(false).when(withdrawalProperties).enabled();
        Long rejoined=registered(id); userService.completeOnboarding(rejoined,"재가입자");
        assertThat(credits.balance(rejoined).total()).isZero();
    }

    @Test
    void rollbackAfterAllDeletesRestoresContentSessionsAndOutbox() {
        Long user=member(); graph(user,LocalDate.of(2026,10,1));
        var token=tokens.login(user);
        new TransactionTemplate(manager).executeWithoutResult(status->{
            withdraw(user); status.setRollbackOnly();
        });
        assertThat(users.findById(user).orElseThrow().isWithdrawn()).isFalse();
        assertThat(count("dreams",user)).isEqualTo(1);
        assertThat(count("image_cleanup_requests",user)).isZero();
        assertThat(tokens.refresh(token.refreshToken()).response().user().userId()).isEqualTo(user);
    }

    @Test
    void concurrentWithdrawalAndNicknameUpdateCannotResurrectUser() throws Exception {
        Long user=member();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{ gate.await(); withdraw(user); return true; });
            var b=pool.submit(()->{ gate.await(); try { userService.updateNickname(user,"새닉네임"); } catch(BusinessException ignored) {} return true; });
            gate.countDown(); assertThat(a.get(15,TimeUnit.SECONDS)).isTrue(); b.get(15,TimeUnit.SECONDS);
        }
        assertThat(users.findById(user).orElseThrow().getNickname()).isNull();
    }

    @Test
    void statementCountDoesNotGrowWithDreamCount() {
        long small=statementCount(1),large=statementCount(80);
        assertThat(large).isEqualTo(small);
    }
    long statementCount(int size) {
        Long user=member();
        for(int i=0;i<size;i++) seed("dreams",Map.of("user_id",user,"dreamed_at",LocalDate.of(2026,1,1).plusDays(i),
                "record_status","COMPLETED","analysis_status","PENDING"));
        var stats=emf.unwrap(SessionFactory.class).getStatistics(); stats.clear();
        withdraw(user);
        return stats.getPrepareStatementCount();
    }

    @Test
    void purgesNonFinancialTombstoneAfterCleanupButKeepsSignupHistory() {
        var id=identity(); Long user=registered(id); userService.completeOnboarding(user,"몽글이"); withdraw(user);
        String hash=secrets.identity("KAKAO",id.providerUserId());
        unlink.finish(unlink.claim(hash).orElseThrow(),true);
        assertThat(retention.purge(user)).isTrue();
        assertThat(users.findById(user)).isEmpty();
        assertThat(history.existsById(hash)).isTrue();
        // Even an upload arriving after the tombstone purge can still be registered.
        discarded.recordDiscardedUpload(user,123L,"dream-images/"+user+"/123/late.png");
        assertThat(count("image_cleanup_requests",user)).isEqualTo(1);
    }

    @Test
    void fiveYearRetentionWaitsForOpenRefundsAndDeletesExpiredFinancialGraph() {
        Long user=member();
        seed("payment_orders",Map.of("user_id",user,"price",1000L,"credits",10L,"state","CANCELLED"));
        withdraw(user);
        assertThat(retention.purge(user)).isFalse();
        jdbc.update("update payment_orders set updated_at=? where user_id=?",LocalDateTime.now().minusYears(6),user);
        jdbc.update("update users set retention_until=? where id=?",LocalDateTime.now().minusDays(1),user);
        var id=tasks.findAll().stream().filter(t->t.getUserId().equals(user)).findFirst().orElseThrow().getIdentityHash();
        unlink.finish(unlink.claim(id).orElseThrow(),true);
        when(refunds.hasOpenRefunds(user)).thenReturn(true);
        assertThat(retention.purge(user)).isFalse();
        when(refunds.hasOpenRefunds(user)).thenReturn(false);
        assertThat(retention.purge(user)).isTrue();
        assertThat(count("payment_orders",user)).isZero();
    }

    @Test
    void lateOperatorReclamationExtendsRetentionBeforePurge() {
        Long user=member();
        var grant=credits.grant(user,CreditSource.PAYMENT,"late-retention-"+user,10);
        long order=seed("payment_orders",Map.of("user_id",user,"price",1100L,"credits",10L,
                "credit_grant_id",grant.grantId(),"state","CANCELLED"));
        when(refunds.coversRemainingPurchases(user)).thenReturn(true);
        withdraw(user);
        String hash=tasks.findAll().stream().filter(t->t.getUserId().equals(user)).findFirst().orElseThrow().getIdentityHash();
        unlink.finish(unlink.claim(hash).orElseThrow(),true);
        jdbc.update("update payment_orders set updated_at=? where user_id=?",LocalDateTime.now().minusYears(6),user);
        jdbc.update("update credit_ledger set created_at=? where user_id=? and source_type='PAYMENT'",LocalDateTime.now().minusYears(6),user);
        jdbc.update("update users set retention_until=? where id=?",LocalDateTime.now().minusDays(1),user);
        var observed=LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC).withNano(0);
        seed("payment_credit_reclamations",Map.of("order_id",order,"user_id",user,"grant_id",grant.grantId(),
                "amount",10L,"created_at",observed));
        assertThat(retention.purge(user)).isFalse();
        assertThat(users.findById(user).orElseThrow().getRetentionUntil()).isAfterOrEqualTo(observed.plusYears(5));
        assertThat(count("payment_credit_reclamations",user)).isEqualTo(1);
    }

    HttpResponse<String> send(HttpClient client,String token,String method,String path) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(10));
        if(token!=null) request.header("Authorization","Bearer "+token);
        return client.send(request.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    long count(String table,Long user) { return jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Long.class,user); }

    /** Complete FK graph with minimal valid database values; no provider calls or per-row application deletes. */
    void graph(Long u,LocalDate date) {
        long d=seed("dreams",Map.of("user_id",u,"dreamed_at",date,"record_status","COMPLETED","analysis_status","COMPLETED"));
        seed("dream_emotions",Map.of("dream_id",d,"emotion","HAPPY"));
        long a=seed("dream_analyses",Map.of("user_id",u,"dream_id",d,"source_dream_id",d,"status","COMPLETED"));
        seed("dream_analysis_display_keywords",Map.of("analysis_id",a,"keyword","바다"));
        long scene=seed("dream_scenes",Map.of("user_id",u,"dream_id",d,"analysis_id",a,"sequence_no",1));
        long element=seed("dream_entities",Map.of("user_id",u,"dream_id",d,"analysis_id",a,"entity_type","PLACE"));
        seed("dream_scene_entities",Map.of("dream_scene_id",scene,"dream_entity_id",element));
        long story=seed("dream_stories",Map.of("user_id",u,"analysis_id",a,"status","COMPLETED"));
        long version=seed("dream_story_versions",Map.of("story_id",story));
        long image=seed("dream_images",Map.of("user_id",u,"analysis_id",a,"status","PROCESSING"));
        jdbc.update("update dream_images set storage_key=? where id=?","dream-images/"+u+"/"+image+"/result.png",image);
        long world=seed("world_folders",Map.of("user_id",u));
        seed("world_selections",Map.of("world_id",world,"dream_id",d,"dreamed_at",date));
        long bridge=seed("world_bridge_results",Map.of("user_id",u,"before_dream_id",d,"after_dream_id",d,
                "before_version_id",version,"after_version_id",version,"status","COMPLETED"));
        seed("world_compositions",Map.of("user_id",u,"world_id",world,"status","COMPLETED"));
        seed("world_composition_entries",Map.of("user_id",u,"world_id",world,"dream_id",d,"story_version_id",version,"bridge_id",bridge));
        seed("world_summaries",Map.of("user_id",u,"world_id",world,"status","COMPLETED"));
        seed("world_summary_sources",Map.of("user_id",u,"world_id",world,"dream_id",d,"story_version_id",version));
        long wi=seed("world_images",Map.of("user_id",u,"world_id",world,"status","PROCESSING"));
        jdbc.update("update world_images set storage_key=? where id=?","world-images/"+u+"/"+wi+"/result.png",wi);
        seed("world_image_sources",Map.of("user_id",u,"image_id",wi,"dream_id",d,"story_version_id",version));
        seed("recent_interpretations",Map.of("user_id",u,"status","COMPLETED"));
        seed("dream_generation_jobs",Map.of("user_id",u,"dream_id",d,"stage","ANALYSIS","status","QUEUED"));
        seed("ai_generation_logs",Map.of("user_id",u,"task_type","DREAM_STRUCTURE","cost_status","LEGACY"));
    }
    long seed(String table,Map<String,Object> supplied) {
        var values=new LinkedHashMap<String,Object>(supplied);
        if(table.equals("payment_orders")) values.put("currency","KRW");
        jdbc.execute((ConnectionCallback<Void>) c->{
            try(var columns=c.getMetaData().getColumns(c.getCatalog(),null,table,null)) {
                while(columns.next()) {
                    String name=columns.getString("COLUMN_NAME");
                    if(values.containsKey(name) || columns.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls
                            || columns.getString("COLUMN_DEF")!=null || "YES".equals(columns.getString("IS_AUTOINCREMENT"))) continue;
                    int type=columns.getInt("DATA_TYPE");
                    Object value=switch(type) {
                        case Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT,Types.NUMERIC,Types.DECIMAL -> 0L;
                        case Types.BOOLEAN,Types.BIT -> false;
                        case Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE -> LocalDateTime.now();
                        case Types.DATE -> LocalDate.now();
                        default -> {
                            String text=UUID.randomUUID().toString();
                            int size=columns.getInt("COLUMN_SIZE");
                            yield size>0 && size<text.length()?text.substring(0,size):text;
                        }
                    };
                    values.put(name,value);
                }
            }
            return null;
        });
        var insert=new org.springframework.jdbc.core.simple.SimpleJdbcInsert(jdbc).withTableName(table).usingColumns(values.keySet().toArray(String[]::new));
        if(List.of("world_compositions","world_summaries","dream_emotions","dream_scene_entities","dream_analysis_display_keywords").contains(table)) {
            insert.execute(values); return 0;
        }
        return insert.usingGeneratedKeyColumns("id").executeAndReturnKey(values).longValue();
    }
}
