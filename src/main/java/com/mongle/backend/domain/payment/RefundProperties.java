package com.mongle.backend.domain.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mongle.payment.refund")
public record RefundProperties(boolean enabled, String supportEmail) {
    public RefundProperties {
        supportEmail = supportEmail == null ? "" : supportEmail.strip();
        if (!supportEmail.isEmpty() && (supportEmail.length() > 254
                || !supportEmail.matches("[A-Za-z0-9._+%-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))) {
            throw new IllegalArgumentException("환불 문의 이메일 형식을 확인해주세요.");
        }
        if (enabled && supportEmail.isEmpty()) {
            throw new IllegalArgumentException("환불 접수 공개 전에 문의 이메일을 설정해주세요.");
        }
    }
}
