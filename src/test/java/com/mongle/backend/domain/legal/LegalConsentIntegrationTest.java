package com.mongle.backend.domain.legal;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.service.UserService;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.credit.CreditService;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:legal-consents;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.legal.published=true","mongle.legal.enforce=true",
    "mongle.legal.terms-digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "mongle.legal.privacy-digest=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "mongle.credit.enabled=true"})
@ActiveProfiles("test")
public class LegalConsentIntegrationTest {
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @Autowired LegalDocumentRepository documents;
    @Autowired java.time.Clock authClock;
    @Autowired LegalService legal;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean LegalConsentRepository consents;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.mongle.backend.domain.user.withdrawal.WithdrawalProperties withdrawalProperties;
    @Autowired com.mongle.backend.domain.user.withdrawal.WithdrawalService withdrawals;
    @Autowired com.mongle.backend.domain.user.withdrawal.WithdrawalRetention retention;
    @Autowired UserRepository users; @Autowired UserService service; @Autowired TokenService tokens;
    @Autowired CreditService credits; @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    @org.junit.jupiter.api.BeforeAll
    static void seedDocumentRegistry(@Autowired JdbcTemplate jdbc) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"payment_credit_reclamations",null)) {
                if(!tables.next()) new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                        new org.springframework.core.io.ClassPathResource("db/migration/V6__payment_credit_reclamations.sql")).populate(connection);
            }
            return null;
        });
        for(var type:List.of("TERMS","PRIVACY")) {
            String key=LegalDocument.key(type,"2026-11-07-v1");
            if(jdbc.queryForObject("select count(*) from legal_document_versions where id=?",Long.class,key)==0)
                jdbc.update("insert into legal_document_versions(id,document_type,document_version) values(?,?,?)",key,type,"2026-11-07-v1");
        }
    }
    LegalService.Accept request(boolean terms,boolean privacy,boolean age) {
        return new LegalService.Accept(LegalService.TERMS_VERSION,"a".repeat(64),terms,
                LegalService.PRIVACY_VERSION,"b".repeat(64),privacy,age);
    }
    @Test void separateRequiredItemsAndExactVersionsAreStoredOnceUnderConcurrentRequests() throws Exception {
        Long user=users.saveAndFlush(User.register(UUID.randomUUID()+"@legal.test")).getId();
        for(var invalid:List.of(request(false,true,true),request(true,false,true),request(true,true,false)))
            assertThatThrownBy(()->legal.accept(user,invalid)).isInstanceOf(BusinessException.class);
        var stale=new LegalService.Accept("old","a".repeat(64),true,LegalService.PRIVACY_VERSION,"b".repeat(64),true,true);
        assertThatThrownBy(()->legal.accept(user,stale)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select count(*) from legal_consents where user_id=?",Long.class,user)).isZero();
        try(var pool=Executors.newFixedThreadPool(3)) {
            var calls=new ArrayList<Future<?>>();
            for(int i=0;i<3;i++) calls.add(pool.submit(()->legal.accept(user,request(true,true,true))));
            for(var call:calls) call.get(10,TimeUnit.SECONDS);
        }
        var before=jdbc.queryForList("select document_type,document_digest,accepted_at from legal_consents where user_id=? order by id",user);
        legal.accept(user,request(true,true,true));
        assertThat(jdbc.queryForList("select document_type,document_digest,accepted_at from legal_consents where user_id=? order by id",user)).isEqualTo(before);
        assertThat(before).hasSize(2); assertThat(service.getMe(user).termsAccepted()).isTrue();
        assertThat(service.completeOnboarding(user,"법적동의").termsAccepted()).isTrue();
        assertThat(credits.balance(user).total()).isEqualTo(3);
    }
    @Test void aSecondInstanceCannotRebindTextToAnAlreadyAcceptedVersion() {
        Long first=users.saveAndFlush(User.register(UUID.randomUUID()+"@legal.test")).getId();
        legal.accept(first,request(true,true,true));
        Long second=users.saveAndFlush(User.register(UUID.randomUUID()+"@legal.test")).getId();
        var changed=new LegalService(new LegalProperties(true,true,"c".repeat(64),"b".repeat(64)),documents,consents,users,authClock);
        var other=new LegalService.Accept(LegalService.TERMS_VERSION,"c".repeat(64),true,
                LegalService.PRIVACY_VERSION,"b".repeat(64),true,true);
        assertThatThrownBy(()->transaction.execute(status->changed.accept(second,other)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(LegalError.DOCUMENT_CONFLICT));
        assertThat(consents.existsByUserIdAndDocumentTypeAndDocumentVersion(second,"TERMS",LegalService.TERMS_VERSION)).isFalse();
        assertThat(documents.findById(LegalDocument.key("TERMS",LegalService.TERMS_VERSION)).orElseThrow().getDocumentDigest()).isEqualTo("a".repeat(64));
        assertThat(changed.current(users.findById(first).orElseThrow())).isFalse();
        assertThat(changed.response(users.findById(first).orElseThrow()).termsAccepted()).isFalse();
    }
    @Test void failureRollsBackBothConsentsCachedStateAndDocumentBinding() {
        Long user=users.saveAndFlush(User.register(UUID.randomUUID()+"@legal.test")).getId();
        jdbc.update("update legal_document_versions set document_digest=null");
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated-storage-failure"))
                .when(consents).save(org.mockito.ArgumentMatchers.any(LegalConsent.class));
        assertThatThrownBy(()->legal.accept(user,request(true,true,true))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from legal_consents where user_id=?",Long.class,user)).isZero();
        assertThat(users.findById(user).orElseThrow().getTermsVersion()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from legal_document_versions where document_digest is not null",Long.class)).isZero();
        org.mockito.Mockito.reset(consents);
        legal.accept(user,request(true,true,true));
        assertThat(service.getMe(user).termsAccepted()).isTrue();
    }
    @Test void documentReadIsPublicAndReadOnlyWhileConsentPostRequiresAuthentication() throws Exception {
        var before=jdbc.queryForList("select * from legal_document_versions order by id");
        try(var client=HttpClient.newHttpClient()) {
            var metadata=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/legal/documents")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(metadata.statusCode()).isEqualTo(200);
            assertThat(metadata.headers().firstValue("Cache-Control")).contains("no-store");
            var anonymous=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/users/me/agreements"))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(anonymous.statusCode()).isEqualTo(401);
            var spec=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
            var paths=tools.jackson.databind.json.JsonMapper.builder().build().readTree(spec.body()).get("paths");
            var get=paths.get("/api/v1/legal/documents").get("get");
            assertThat(get.get("security")==null || get.get("security").isEmpty()).isTrue();
            assertThat(paths.get("/api/v1/users/me/agreements").get("post").get("security").toString()).contains("bearerAuth");
            assertThat(spec.body()).contains("LegalDocumentCatalog","LegalDocumentMetadata","LegalConsentRequest");
        }
        assertThat(jdbc.queryForList("select * from legal_document_versions order by id")).isEqualTo(before);
    }
    @Test void unpaidWithdrawalDeletesConsentAndFinancialTombstoneRetainsUntilPurge() {
        org.mockito.Mockito.doReturn(true).when(withdrawalProperties).enabled();
        Long unpaid=users.saveAndFlush(User.create(UUID.randomUUID()+"@legal.test","일반회원")).getId();
        legal.accept(unpaid,request(true,true,true));
        withdrawals.withdraw(unpaid);
        assertThat(jdbc.queryForObject("select count(*) from legal_consents where user_id=?",Long.class,unpaid)).isZero();
        assertThat(legal.current(users.findById(unpaid).orElseThrow())).isFalse();
        Long financial=users.saveAndFlush(User.create(UUID.randomUUID()+"@legal.test","거래회원")).getId();
        legal.accept(financial,request(true,true,true));
        jdbc.update("""
            insert into payment_orders(public_id,user_id,request_id,input_hash,notification_token,product_id,product_name,
                price,currency,credits,state,request_deadline,version,created_at,updated_at)
            values(?,?,?,REPEAT('a',64),?,'test-pack','test pack',1100,'KRW',10,'CANCELLED',CURRENT_TIMESTAMP,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """,UUID.randomUUID().toString(),financial,UUID.randomUUID().toString(),UUID.randomUUID().toString());
        withdrawals.withdraw(financial);
        assertThat(jdbc.queryForObject("select count(*) from legal_consents where user_id=?",Long.class,financial)).isEqualTo(2);
        assertThat(retention.purge(financial)).isFalse();
        jdbc.update("update payment_orders set updated_at=? where user_id=?",java.time.LocalDateTime.now().minusYears(6),financial);
        jdbc.update("update users set retention_until=? where id=?",java.time.LocalDateTime.now().minusDays(1),financial);
        assertThat(retention.purge(financial)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from legal_consents where user_id=?",Long.class,financial)).isZero();
    }
    @Test void existingMemberMustConsentButCanReadBalanceAndHistoryWithoutIt() throws Exception {
        Long user=users.saveAndFlush(User.create(UUID.randomUUID()+"@legal.test","기존회원")).getId();
        assertThat(tokens.login(user).response().user().termsAccepted()).isFalse();
        try(var client=HttpClient.newHttpClient()) {
            String token=tokens.login(user).response().accessToken();
            var blocked=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/archives"))
                    .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(blocked.statusCode()).isEqualTo(403); assertThat(blocked.body()).contains("LEGAL_403_1");
            var paidRights=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/payments/orders"))
                    .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(paidRights.statusCode()).isEqualTo(200);
            var withdrawal=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/users/me"))
                    .header("Authorization","Bearer "+token).DELETE().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(withdrawal.statusCode()).isEqualTo(503); // feature flag, not missing agreement
            assertThat(withdrawal.body()).contains("USER_503_1");
            var balance=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/credits/balance"))
                    .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(balance.statusCode()).isEqualTo(200);
            var documents=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/legal/documents"))
                    .GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(documents.statusCode()).isEqualTo(200); assertThat(documents.body()).contains("TERMS","PRIVACY");
            var body=""" 
                    {"termsVersion":"2026-11-07-v1","termsDigest":"%s","termsAccepted":true,
                     "privacyVersion":"2026-11-07-v1","privacyDigest":"%s","privacyAccepted":true,"age14OrOlder":true}
                    """.formatted("a".repeat(64),"b".repeat(64));
            var accepted=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/users/me/agreements"))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(accepted.statusCode()).isEqualTo(200); assertThat(accepted.body()).contains("\"termsAccepted\":true");
            var productResponse=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/payments/products"))
                    .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(productResponse.statusCode()).isEqualTo(200);
            assertThat(tokens.refresh(tokens.login(user).refreshToken()).response().user().termsAccepted()).isTrue();
        }
        assertThat(credits.balance(user).total()).isZero();
    }
}
