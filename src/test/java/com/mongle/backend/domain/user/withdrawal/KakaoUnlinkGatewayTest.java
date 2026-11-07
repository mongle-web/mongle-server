package com.mongle.backend.domain.user.withdrawal;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class KakaoUnlinkGatewayTest {
    HttpServer server;
    String response; int status;
    AtomicReference<String> form=new AtomicReference<>(),auth=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/unlink",exchange->{
            form.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    @AfterEach void close() { server.stop(0); }
    WithdrawalProperties props() {
        return new WithdrawalProperties(true,false,"identity-key-12345678901234567890123456789",
                "encryption-key-1234567890123456789012345","test-admin","http://127.0.0.1:"+server.getAddress().getPort()+"/unlink",
                Duration.ofSeconds(10),Duration.ofSeconds(1));
    }
    @Test void postsAdminAuthenticatedFormAndChecksReturnedId() {
        status=200; response="{\"id\":123}";
        try(var gateway=new KakaoUnlinkGateway(props())) {
            assertThat(gateway.unlink("123")).isTrue();
            assertThat(form.get()).contains("target_id_type=user_id","target_id=123");
            assertThat(auth.get()).isEqualTo("KakaoAK test-admin");
            response="{\"id\":456}"; assertThat(gateway.unlink("123")).isFalse();
        }
    }
    @Test void alreadyUnlinkedIsSuccessButGenericFailuresAreRetried() {
        try(var gateway=new KakaoUnlinkGateway(props())) {
            status=400; response="{\"code\":-101}"; assertThat(gateway.unlink("123")).isTrue();
            status=401; response="{\"code\":-401}"; assertThat(gateway.unlink("123")).isFalse();
            status=500; response="private provider error"; assertThat(gateway.unlink("123")).isFalse();
        }
    }
    @Test void encryptionUsesUniqueNonceAndRejectsTamperingWithoutLeakingValues() {
        var secrets=new WithdrawalSecrets(props());
        String first=secrets.encrypt("123"),second=secrets.encrypt("123");
        assertThat(first).isNotEqualTo(second);
        assertThat(secrets.decrypt(first)).isEqualTo("123");
        assertThat(secrets.identity("KAKAO","123")).isEqualTo(secrets.identity("KAKAO","123")).hasSize(64);
        assertThat(secrets.identity("KAKAO","124")).isNotEqualTo(secrets.identity("KAKAO","123"));
        assertThatThrownBy(()->secrets.decrypt(first.substring(1))).hasMessage("연결 해제 대상 확인 실패");
        assertThat(props().toString()).doesNotContain("test-admin","identity-key","encryption-key");
    }
}
