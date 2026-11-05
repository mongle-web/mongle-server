package com.mongle.backend.domain.payment;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@ExtendWith(OutputCaptureExtension.class)
class PayAppGatewayTest {
    static PayAppProperties properties(Duration timeout) {
        return new PayAppProperties(true,"seller","link-secret+/=","value-secret+/=","request-secret+/=",
                URI.create("https://api.mongle.test/api/v1/payments/payapp/feedback"),URI.create("https://mongle.test/payment"),java.util.List.of("card","kakaopay"),Duration.ofMillis(100),timeout);
    }
    @Test void sendsEncodedServerOrderOverAsyncHttpAndValidatesPaymentUrl() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var posted=new AtomicReference<String>();
        server.createContext("/",x -> {
            posted.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            var body="state=1&mul_no=345&payurl=https%3A%2F%2Fpayapp.kr%2Fcheckout".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,body.length); x.getResponseBody().write(body); x.close();
        }); server.start();
        var clock=Clock.fixed(Instant.parse("2026-10-25T15:00:01Z"),ZoneOffset.UTC);
        try(var gateway=new GatewayResource(new PayAppGateway(properties(Duration.ofSeconds(2)),clock,HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort())))) {
            var result=gateway.value.request(new PaymentGateway.Command("order-1","token-1","꿈 & 충전",1000,"01012345678")).get(5,TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(PaymentGateway.Outcome.READY);
            var form=PayAppForm.decode(posted.get());
            assertThat(form).containsEntry("goodname","꿈 & 충전").containsEntry("var1","order-1").containsEntry("var2","token-1").containsEntry("checkretry","y").containsEntry("openpaytype","card,kakaopay");
            assertThat(form.get("hash")).isEqualTo(PaymentHashes.sha256("seller20261026000001request-secret+/="));
            assertThat(form.get("returnurl")).isEqualTo("https://mongle.test/payment?orderId=order-1");
            assertThat(form).containsEntry("userid","seller").containsEntry("linkkey","link-secret+/=")
                    .containsEntry("requestdate","20261026000001").containsEntry("price","1000");
            assertThat(form).doesNotContainKey("requestkey");
        } finally { server.stop(0); }
        for(String url:new String[]{"http://payapp.kr/pay","https://payapp.kr.evil.test/pay","https://user@payapp.kr/pay","https://evil.test/pay"})
            assertThat(PayAppGateway.validPaymentUrl(url)).isFalse();
        assertThat(PayAppGateway.validPaymentUrl("https://lite.payapp.kr/pay")).isTrue();
    }
    @Test void diagnosticsExposeOnlyOrderOutcomeStatusAndAllowedErrno(CapturedOutput output) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var mode=new AtomicInteger(); var count=new AtomicInteger();
        server.createContext("/",x -> {
            count.incrementAndGet();
            String errno=mode.get()==0?"70020":"secret-error-01012345678";
            var body=PayAppForm.encode(java.util.Map.of("state","0","errno",errno,
                    "errorMessage","link-secret+/= value-secret+/= request-secret+/= 01012345678 body-secret")).getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(mode.get()==2?503:200,body.length);
            x.getResponseBody().write(body); x.close();
        }); server.start();
        String id="2233e4d7-992c-48e3-b519-334fc754e925";
        try(var gateway=new GatewayResource(new PayAppGateway(properties(Duration.ofSeconds(2)),Clock.systemUTC(),HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort())))) {
            for(int i=0;i<3;i++) {
                mode.set(i);
                var result=gateway.value.request(new PaymentGateway.Command(id,"token-secret","충전",1100,"01012345678")).get(5,TimeUnit.SECONDS);
                assertThat(result.outcome()).isEqualTo(i==2?PaymentGateway.Outcome.UNKNOWN:PaymentGateway.Outcome.REJECTED);
            }
            assertThat(count.get()).isEqualTo(3);
        } finally { server.stop(0); }
        assertThat(output.getAll()).contains("orderId="+id,"outcome=REJECTED","errno=70020",
                "outcome=UNKNOWN reason=HTTP_FAILED httpStatus=503 errno=NONE");
        assertThat(output.getAll()).doesNotContain("secret-error","body-secret","link-secret","value-secret", "request-secret","01012345678","token-secret","errorMessage","hash=");
    }
    @Test void malformedOversizedOrRedirectedResponsesAreUnknownWithoutRetry() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var count=new AtomicInteger(); var mode=new AtomicInteger();
        server.createContext("/",x -> {
            count.incrementAndGet();
            if(mode.get()==2) { x.getResponseHeaders().add("Location","/another"); x.sendResponseHeaders(302,-1); x.close(); return; }
            var body=(mode.get()==0?"state=1&state=1": "x".repeat(40000)).getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,0); x.getResponseBody().write(body); x.close();
        }); server.start();
        try(var gateway=new GatewayResource(new PayAppGateway(properties(Duration.ofSeconds(2)),Clock.systemUTC(),HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort())))) {
            for(int i=0;i<3;i++) {
                mode.set(i);
                assertThat(gateway.value.request(new PaymentGateway.Command("o","t","충전",1000,"01012345678")).get(5,TimeUnit.SECONDS).outcome()).isEqualTo(PaymentGateway.Outcome.UNKNOWN);
            }
            assertThat(count.get()).isEqualTo(3);
        } finally { server.stop(0); }
    }
    @Test void wholeResponseBudgetCancelsStalledBodyAndDoesNotExposeSecrets() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var release=new CountDownLatch(1); var count=new AtomicInteger();
        server.createContext("/",x -> { count.incrementAndGet(); x.sendResponseHeaders(200,0); try { release.await(3,TimeUnit.SECONDS); }catch(InterruptedException e){Thread.currentThread().interrupt();} x.close(); }); server.start();
        var p=properties(Duration.ofMillis(200));
        try(var gateway=new GatewayResource(new PayAppGateway(p,Clock.systemUTC(),HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort())))) {
            assertThat(gateway.value.request(new PaymentGateway.Command("o","secret","충전",1000,"01012345678")).get(3,TimeUnit.SECONDS).outcome()).isEqualTo(PaymentGateway.Outcome.UNKNOWN);
            assertThat(count.get()).isLessThanOrEqualTo(1);
            assertThat(p.toString()).doesNotContain("link-secret+/=","value-secret+/=","request-secret+/=");
            assertThat(new PaymentOrderRequest(java.util.UUID.randomUUID(),"pack","01012345678").toString()).doesNotContain("01012345678");
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void activeConfigurationRejectsMissingKeysAndUnsafeUrls() {
        assertThatThrownBy(() -> new PayAppProperties(true,"s","k","v","r",URI.create("http://unsafe/feedback"),URI.create("https://return.test"),java.util.List.of("card"),Duration.ofSeconds(1),Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayAppProperties(true,"s","k","v","",URI.create("https://api.test/api/v1/payments/payapp/feedback"),URI.create("https://return.test"),java.util.List.of("card"),Duration.ofSeconds(1),Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsEmptyDuplicateAndNonCardOrEasyPayMethods() {
        var p=properties(Duration.ofSeconds(2));
        for(var methods:java.util.List.of(java.util.List.<String>of(),java.util.List.of("card","card"),
                java.util.List.of("phone"),java.util.List.of("vbank"),java.util.List.of("rbank"),
                java.util.List.of("myaccount"),java.util.List.of("unknown"),java.util.List.of("CARD"))) {
            assertThatThrownBy(() -> new PayAppProperties(p.enabled(),p.sellerId(),p.linkKey(),p.linkValue(),
                    p.requestKey(),p.feedbackUrl(),p.returnUrl(),methods,p.connectTimeout(),p.totalTimeout()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var methods=new java.util.ArrayList<>(java.util.List.of("card","tosspay"));
        var immutable=new PayAppProperties(p.enabled(),p.sellerId(),p.linkKey(),p.linkValue(),p.requestKey(),
                p.feedbackUrl(),p.returnUrl(),methods,p.connectTimeout(),p.totalTimeout());
        methods.clear();
        assertThat(immutable.openPayTypes()).containsExactly("card","tosspay");
    }
    record GatewayResource(PayAppGateway value) implements AutoCloseable { public void close() { value.close(); } }
}
