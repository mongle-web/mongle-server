package com.mongle.backend.operations.credit;

import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.config.JpaConfig;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Map;

import javax.sql.DataSource;

/** 명시한 JPA/크레딧 빈만 등록한다. Boot·컴포넌트 스캔·서버·스케줄러를 시작하지 않는다. */
final class TestCreditContext implements AutoCloseable {
    private final AnnotationConfigApplicationContext context;

    private TestCreditContext(AnnotationConfigApplicationContext context) {
        this.context = context;
    }

    static TestCreditContext open(DataSource source, CreditProperties properties) {
        var context = new AnnotationConfigApplicationContext();
        try {
            context.registerBean(DataSource.class, () -> source);
            context.registerBean(CreditProperties.class, () -> properties);
            context.register(
                    Infrastructure.class, CreditService.class, PromotionCreditOperations.class);
            context.refresh();
            return new TestCreditContext(context);
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }

    PromotionCreditOperations.Result execute(TestCreditRequest request) {
        var operations = context.getBean(PromotionCreditOperations.class);
        return request.operation().equals("grant")
                ? operations.grant(request.userId(), request.amount(), request.requestKey())
                : operations.preview(request.userId(), request.amount(), request.requestKey());
    }

    @Override
    public void close() {
        context.close();
    }

    // @Configuration/@Component를 붙이지 않아 일반 서버의 컴포넌트 스캔에서 제외한다.
    @EnableTransactionManagement
    @EnableJpaRepositories(
            basePackageClasses = {CreditAccountRepository.class, UserRepository.class})
    @Import(JpaConfig.class)
    static class Infrastructure {
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(
                    CreditAccount.class.getPackageName(), User.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(
                    Map.of(
                            "hibernate.hbm2ddl.auto",
                            "validate",
                            "hibernate.jdbc.time_zone",
                            "UTC",
                            "hibernate.show_sql",
                            false,
                            "jakarta.persistence.query.timeout",
                            10000));
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
