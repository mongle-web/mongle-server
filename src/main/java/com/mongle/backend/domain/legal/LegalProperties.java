package com.mongle.backend.domain.legal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mongle.legal")
public record LegalProperties(
        @DefaultValue("false") boolean published,
        @DefaultValue("false") boolean enforce,
        @DefaultValue("") String termsDigest,
        @DefaultValue("") String privacyDigest) {

    public LegalProperties {
        if (enforce && !published) {
            throw new IllegalArgumentException("약관 공개 후 동의 필수를 활성화해주세요.");
        }
        if (published && (!validDigest(termsDigest) || !validDigest(privacyDigest))) {
            throw new IllegalArgumentException("공개한 두 문서의 SHA-256을 설정해주세요.");
        }
    }

    private static boolean validDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
