package com.mongle.backend.domain.payment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentProperties.class,PayAppProperties.class})
public class PaymentConfiguration {
    @Bean @ConditionalOnMissingBean(PaymentGateway.class)
    PaymentGateway paymentGateway(PayAppProperties properties,Clock authClock) {
        if(properties.enabled()) return new PayAppGateway(properties,authClock);
        return new PaymentGateway() {
            public boolean available() { return false; }
            public CompletableFuture<Result> request(Command command) { return CompletableFuture.completedFuture(Result.unknown()); }
        };
    }
}
