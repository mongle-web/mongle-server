package com.mongle.backend.domain.world.image;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorldImageProperties.class)
public class WorldImageConfiguration {}
