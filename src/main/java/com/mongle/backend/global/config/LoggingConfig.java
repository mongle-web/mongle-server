package com.mongle.backend.global.config;

import com.mongle.backend.global.logging.HttpRequestLoggingFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 요청 로그 필터를 Spring Security보다 먼저 실행하고 비동기 재디스패치도 추적한다. */
@Configuration(proxyBeanMethods = false)
public class LoggingConfig {
    @Bean
    FilterRegistrationBean<HttpRequestLoggingFilter> httpRequestLoggingFilter() {
        var registration = new FilterRegistrationBean<>(new HttpRequestLoggingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
