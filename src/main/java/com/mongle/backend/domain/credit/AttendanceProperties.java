package com.mongle.backend.domain.credit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mongle.credit.attendance")
public record AttendanceProperties(
        @DefaultValue("false") boolean enabled, @DefaultValue("1") long amount) {
    public AttendanceProperties {
        if (amount < 1) throw new IllegalArgumentException("출석 보상 지급량은 양수여야 합니다.");
    }
}
