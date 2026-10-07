package com.mongle.backend.domain.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 가격표 설정을 Spring Bean으로 등록한다. HTTP 호출 설정과 비용 정책의 책임을 나눈다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiPricingProperties.class)
public class AiPricingConfig { }
