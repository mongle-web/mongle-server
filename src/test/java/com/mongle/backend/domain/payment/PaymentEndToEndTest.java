package com.mongle.backend.domain.payment;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.credit.CreditService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:payment-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.credit.enabled=true", "mongle.payment.enabled=true",
    "mongle.payment.products[0].id=pack", "mongle.payment.products[0].name=테스트충전",
    "mongle.payment.products[0].price=1000", "mongle.payment.products[0].credits=7", "mongle.payment.products[0].enabled=true",
    "mongle.payment.payapp.enabled=true", "mongle.payment.payapp.seller-id=seller", "mongle.payment.payapp.link-key=link-secret",
    "mongle.payment.payapp.link-value=value-secret", "mongle.payment.payapp.request-key=request-secret",
    "mongle.payment.payapp.feedback-url=https://api.mongle.test/api/v1/payments/payapp/feedback",
    "mongle.payment.payapp.return-url=https://mongle.test/payment"})
@ActiveProfiles("test")
class PaymentEndToEndTest {
    static final AtomicInteger applicationPort=new AtomicInteger();
    static final AtomicInteger providerCalls=new AtomicInteger();
    static final AtomicReference<String> callbackResponse=new AtomicReference<>();
    static HttpServer provider;
    @TestConfiguration(proxyBeanMethods=false) static class Config {
        @Bean @Primary PaymentGateway localPayAppGateway(PayAppProperties p,Clock authClock) throws Exception {
            provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            provider.createContext("/",x -> {
                providerCalls.incrementAndGet();
                var request=PayAppForm.decode(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                var form=new LinkedHashMap<String,String>();
                for(String k:List.of("userid","var1","var2","price")) form.put(k,request.get(k));
                form.put("linkkey","link-secret"); form.put("linkval","value-secret");
                form.put("responsedate",request.get("requestdate")); form.put("hash",request.get("hash"));
                form.put("mul_no","987654321"); form.put("pay_state","4");
                try(var client=HttpClient.newHttpClient()) {
                    var reply=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+applicationPort.get()+"/api/v1/payments/payapp/feedback"))
                            .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(PayAppForm.encode(form))).build(),HttpResponse.BodyHandlers.ofString());
                    callbackResponse.set(reply.statusCode()+":"+reply.body());
                } catch(Exception e) { callbackResponse.set("failed"); }
                var body="state=1&mul_no=987654321&payurl=https%3A%2F%2Fpayapp.kr%2Fcheckout".getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(200,body.length); x.getResponseBody().write(body); x.close();
            }); provider.start();
            return new PayAppGateway(p,authClock,HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+provider.getAddress().getPort()));
        }
    }
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @Autowired CreditService credits;
    @Value("${local.server.port}") int port;
    @AfterAll static void stop() { if(provider!=null) provider.stop(0); }
    @Test void realOrderEndpointAndAdapterAcceptEarlyNotificationAndNeverChargeTwice() throws Exception {
        applicationPort.set(port);
        Long user=users.saveAndFlush(User.create(UUID.randomUUID()+"@e2e.test","결제")).getId();
        var json=JsonMapper.builder().build();
        String body=json.writeValueAsString(new PaymentOrderRequest(UUID.randomUUID(),"pack","01012345678"));
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/payments/orders"))
                .header("Authorization","Bearer "+tokens.login(user).response().accessToken())
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try(var client=HttpClient.newHttpClient()) {
            var result=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).isEqualTo(200); assertThat(callbackResponse.get()).isEqualTo("200:SUCCESS");
            var data=json.readTree(result.body()).get("data");
            assertThat(data.get("state").asString()).isEqualTo("PAID"); assertThat(data.get("creditGranted").asBoolean()).isTrue();
            assertThat(data.get("paymentUrl").isNull()).isTrue();
            assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).body()).isEqualTo(result.body());
            assertThat(providerCalls.get()).isEqualTo(1); assertThat(credits.balance(user).available()).isEqualTo(7);
        }
    }
}
