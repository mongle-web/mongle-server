package com.mongle.backend.domain.user.withdrawal;

import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(WithdrawalProperties.class)
public class WithdrawalConfiguration {
    @Bean @ConditionalOnMissingBean(WithdrawalRefundPolicy.class)
    WithdrawalRefundPolicy withdrawalRefundPolicy() {
        return new WithdrawalRefundPolicy() {
            public boolean coversRemainingPurchases(Long user) { return false; }
            public boolean hasOpenRefunds(Long user) { return false; }
        };
    }
    @Bean(destroyMethod="close")
    KakaoUnlinkGateway kakaoUnlinkGateway(WithdrawalProperties properties) { return new KakaoUnlinkGateway(properties); }
    @Bean(destroyMethod="close")
    WithdrawalWorker withdrawalWorker(WithdrawalProperties p,UnlinkTransactions t,KakaoUnlinkGateway g,
            WithdrawalSecrets s,WithdrawalRetention r) { return new WithdrawalWorker(p,t,g,s,r); }
}
