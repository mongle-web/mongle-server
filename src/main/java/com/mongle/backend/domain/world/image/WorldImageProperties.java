package com.mongle.backend.domain.world.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mongle.world.image")
public record WorldImageProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("24000") int maxInputCharacters) {
    public WorldImageProperties {
        if (maxInputCharacters < 1000 || maxInputCharacters > 24000)
            throw new IllegalArgumentException("세계관 이미지 입력 상한은 1000~24000자입니다.");
    }
}
