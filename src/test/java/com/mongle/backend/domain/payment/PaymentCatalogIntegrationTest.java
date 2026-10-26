package com.mongle.backend.domain.payment;

import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.auth.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:payment-catalog;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.payment.products[0].id=pack", "mongle.payment.products[0].name=테스트충전",
    "mongle.payment.products[0].price=1000", "mongle.payment.products[0].credits=7", "mongle.payment.products[0].enabled=true"})
@ActiveProfiles("test")
class PaymentCatalogIntegrationTest {
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @Value("${local.server.port}") int port;
    @Test void catalogRequiresOnboardingAndDocumentsActualResponse() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var uri=URI.create("http://localhost:"+port+"/api/v1/payments/products");
            assertThat(client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            Long id=users.saveAndFlush(User.create(UUID.randomUUID()+"@payment.test","결제")).getId();
            var response=client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+tokens.login(id).response().accessToken()).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            var json=JsonMapper.builder().build();
            assertThat(json.readTree(response.body()).at("/data/0/credits").asLong()).isEqualTo(7);
            var orderUri=URI.create("http://localhost:"+port+"/api/v1/payments/orders");
            var disabled=client.send(HttpRequest.newBuilder(orderUri).header("Authorization","Bearer "+tokens.login(id).response().accessToken())
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(new PaymentOrderRequest(UUID.randomUUID(),"pack","01012345678")))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(disabled.statusCode()).isEqualTo(503);
            var spec=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(json.readTree(spec.body()).get("paths").get("/api/v1/payments/products").get("get").get("security").size()).isPositive();
            Long pending=users.saveAndFlush(User.register(UUID.randomUUID()+"@pending.test")).getId();
            assertThat(client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+tokens.login(pending).response().accessToken()).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }
}
