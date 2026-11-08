package com.mongle.backend.operations.payment;

import com.mongle.backend.domain.credit.CreditAccount;
import com.mongle.backend.domain.credit.CreditAccountRepository;
import com.mongle.backend.domain.credit.CreditProperties;
import com.mongle.backend.domain.credit.CreditService;
import com.mongle.backend.domain.payment.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.config.JpaConfig;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Only accounting infrastructure and the normal payment handlers run in the one-shot command. */
final class PaymentRecoveryContext {
    private PaymentRecoveryContext() {}

    static AnnotationConfigApplicationContext open(DataSource source, PayAppProperties payApp,
            CreditProperties credits, Clock clock) {
        var context = new AnnotationConfigApplicationContext();
        try {
            context.registerBean(DataSource.class, () -> source);
            context.registerBean(PayAppProperties.class, () -> payApp);
            context.registerBean(CreditProperties.class, () -> credits);
            context.registerBean(Clock.class, () -> clock);
            context.registerBean(PaymentProperties.class, () -> new PaymentProperties(false, List.of()));
            context.register(Infrastructure.class, CreditService.class, PaymentCatalog.class,
                    PaymentTransactions.class, PayAppFeedbackVerifier.class, PaymentFeedbackService.class,
                    PaymentRecoveryService.class);
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    // Explicitly registered above, outside ordinary component scanning.
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {PaymentOrderRepository.class, CreditAccountRepository.class, UserRepository.class})
    @Import(JpaConfig.class)
    static class Infrastructure {
        @Bean
        PaymentGateway paymentGateway() {
            return new PaymentGateway() {
                @Override public boolean available() { return false; }
                @Override public CompletableFuture<Result> request(Command command) {
                    throw new IllegalStateException("Outbound payment requests are unavailable in recovery");
                }
            };
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(CreditAccount.class.getPackageName(), User.class.getPackageName(),
                    PaymentOrder.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.jdbc.time_zone", "UTC",
                    "hibernate.show_sql", false, "jakarta.persistence.query.timeout", 5000));
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            var manager = new JpaTransactionManager(factory);
            manager.setDefaultTimeout(15);
            return manager;
        }
    }
}
