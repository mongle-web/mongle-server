package com.mongle.backend.domain.user.withdrawal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("mongle.withdrawal")
public record WithdrawalProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean workerEnabled, @DefaultValue("") String identityKey,
        @DefaultValue("") String encryptionKey, @DefaultValue("") String kakaoAdminKey,
        @DefaultValue("https://kapi.kakao.com/v1/user/unlink") String unlinkUrl,
        @DefaultValue("10s") Duration pollInterval, @DefaultValue("5s") Duration requestTimeout) {
    public WithdrawalProperties {
        if (pollInterval.isNegative() || pollInterval.isZero() || requestTimeout.isNegative()
                || requestTimeout.isZero() || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("탈퇴 작업 실행 시간이 올바르지 않습니다.");
        boolean keysNeeded=enabled || workerEnabled || !identityKey.isBlank() || !encryptionKey.isBlank();
        if (keysNeeded && (identityKey.length() < 32 || encryptionKey.length() < 32))
            throw new IllegalArgumentException("탈퇴 기능에는 서로 다른 전용 식별/암호화 키(각 32자 이상)가 필요합니다.");
        if (keysNeeded && identityKey.equals(encryptionKey))
            throw new IllegalArgumentException("식별 키와 암호화 키는 분리해야 합니다.");
    }
    @Override public String toString() { return "WithdrawalProperties[enabled=" + enabled + ", workerEnabled=" + workerEnabled + "]"; }
}
