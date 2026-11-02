package com.mongle.backend.domain.credit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CreditProperties.class, AttendanceProperties.class})
public class CreditConfiguration {}
