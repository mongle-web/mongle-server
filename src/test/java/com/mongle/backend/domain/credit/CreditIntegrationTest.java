package com.mongle.backend.domain.credit;

import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.global.error.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:credit-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.credit.enabled=true", "mongle.credit.expiry-policy=NO_EXPIRY"})
@ActiveProfiles("test")
class CreditIntegrationTest {
    @Autowired CreditService credits;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired CreditAccountRepository accounts;
    @Autowired CreditGrantRepository grants;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    Long user;
    final JsonMapper json = JsonMapper.builder().build();
    @BeforeEach void setup() { user = newUser(); }

    @Test void normalServerDoesNotRegisterOperatorCommandInfrastructure() {
        assertThat(context.getBeansOfType(PromotionCreditOperations.class)).isEmpty();
        assertThat(context.getBeansOfType(javax.sql.DataSource.class)).hasSize(1);
    }
    @Test void balanceStartsAtZeroWithoutCreatingAnAccount() {
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(0,0,0));
        assertThat(accounts.existsById(user)).isFalse();
    }
    @Test void duplicateGrantIsIdempotentAndKeepsPurchaseSource() {
        var a = credits.grant(user, CreditSource.PAYMENT, "order-A", 30);
        assertThat(credits.grant(user, CreditSource.PAYMENT, "order-A", 30)).isEqualTo(a);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(30,0,30));
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id=?", Long.class,user)).isEqualTo(1);
        assertThat(grants.findById(a.grantId()).orElseThrow().getSourceKey()).isEqualTo("order-A");
    }
    @Test void sourceCannotBeReusedForAnotherAmountOrOwner() {
        credits.grant(user, CreditSource.PAYMENT, "order-B", 10);
        error(() -> credits.grant(user, CreditSource.PAYMENT, "order-B", 11), CreditErrorCode.REQUEST_CONFLICT);
        Long other = newUser();
        error(() -> credits.grant(other, CreditSource.PAYMENT, "order-B", 10), CreditErrorCode.REQUEST_CONFLICT);
        assertThat(credits.balance(other).total()).isZero();
    }
    @Test void sourceKeysAreCaseSensitiveAndSourceKindsAreSeparate() {
        credits.grant(user, CreditSource.PAYMENT, "Order-C", 2);
        credits.grant(user, CreditSource.PAYMENT, "order-c", 3);
        credits.grant(user, CreditSource.PROMOTION, "Order-C", 5);
        assertThat(credits.balance(user).available()).isEqualTo(10);
    }
    @Test void invalidGrantAndOverflowDoNotChangeBalanceOrLedger() {
        for (long amount : new long[]{0,-1}) error(() -> credits.grant(user, CreditSource.PAYMENT, "invalid", amount),CreditErrorCode.INVALID_INPUT);
        error(() -> credits.grant(user, CreditSource.PAYMENT, " ", 1),CreditErrorCode.INVALID_INPUT);
        credits.grant(user, CreditSource.PROMOTION, "max", Long.MAX_VALUE);
        error(() -> credits.grant(user, CreditSource.PROMOTION, "overflow", 1),CreditErrorCode.CAPACITY_EXCEEDED);
        assertThat(credits.balance(user).available()).isEqualTo(Long.MAX_VALUE);
    }
    @Test void grantJoinsCallerTransactionAndRollsBackWithPayment() {
        assertThatThrownBy(() -> transaction.execute(s -> {
            credits.grant(user, CreditSource.PAYMENT, "rollback", 20);
            throw new IllegalStateException("결제 저장 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(credits.balance(user).total()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id=?",Long.class,user)).isZero();
    }
    @Test void balanceHttpRequiresAuthenticationAndDoesNotExposeOtherAccountsOrMutationApis() throws Exception {
        credits.grant(user,CreditSource.PROMOTION,"mine",7);
        Long other = newUser(); credits.grant(other,CreditSource.PROMOTION,"other",90);
        String token = tokens.login(user).response().accessToken();
        var own = send(token,"GET","/api/v1/credits/balance?userId="+other);
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(json.readTree(own.body()).at("/data/available").asLong()).isEqualTo(7);
        assertThat(own.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(send(null,"GET","/api/v1/credits/balance").statusCode()).isEqualTo(401);
        assertThat(send(token,"POST","/api/v1/credits/grant").statusCode()).isEqualTo(404);
        assertThat(credits.balance(user).available()).isEqualTo(7);
    }
    @Test void swaggerDocumentsBalanceSuccessAndAuthentication() throws Exception {
        var spec=json.readTree(send(null,"GET","/v3/api-docs").body());
        var op=spec.get("paths").get("/api/v1/credits/balance").get("get");
        assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asString()).contains("CreditBalance");
        assertThat(op.get("security").size()).isPositive();
    }
    @Test void transactionsPageNewestFirstExcludesOtherUsersAndKeepsSnapshotBalances() {
        credits.grant(user,CreditSource.PAYMENT,"page-1",10);
        credits.grant(user,CreditSource.PROMOTION,"page-2",5);
        credits.grant(newUser(),CreditSource.PAYMENT,"page-other",100);
        credits.grant(user,CreditSource.PAYMENT,"page-3",20);
        var page=credits.transactions(user,null,2);
        assertThat(page.items()).extracting(CreditTransaction::availableAfter).containsExactly(35L,15L);
        assertThat(page.items()).extracting(CreditTransaction::createdAt).doesNotContainNull();
        assertThat(page.hasNext()).isTrue();
        var rest=credits.transactions(user,page.nextBeforeId(),2);
        assertThat(rest.items()).extracting(CreditTransaction::availableAfter).containsExactly(10L);
        assertThat(rest.hasNext()).isFalse(); assertThat(rest.nextBeforeId()).isNull();
        assertThat(credits.transactions(newUser(),null,20).items()).isEmpty();
    }
    @Test void transactionsHttpValidatesPageAndDoesNotExposeProviderKeys() throws Exception {
        credits.grant(user,CreditSource.PAYMENT,"private-order",5);
        String token=tokens.login(user).response().accessToken();
        var response=send(token,"GET","/api/v1/credits/transactions?size=1");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("private-order","sourceKey","sourceHash");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        for(String query:List.of("size=0","size=51","beforeId=0","beforeId=-1"))
            assertThat(send(token,"GET","/api/v1/credits/transactions?"+query).statusCode()).isEqualTo(400);
        assertThat(send(null,"GET","/api/v1/credits/transactions").statusCode()).isEqualTo(401);
        var spec=json.readTree(send(null,"GET","/v3/api-docs").body());
        var op=spec.get("paths").get("/api/v1/credits/transactions").get("get");
        assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asString()).contains("CreditTransactionPage");
    }
    Long newUser() { return users.saveAndFlush(User.create(UUID.randomUUID()+"@credit.test","크레딧")).getId(); }
    void error(Runnable r, ErrorCode code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
    HttpResponse<String> send(String token,String method,String path) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)b.header("Authorization","Bearer "+token);
        try(var c=HttpClient.newHttpClient()) { return c.send(b.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
