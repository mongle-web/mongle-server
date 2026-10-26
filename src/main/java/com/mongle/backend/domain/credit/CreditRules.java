package com.mongle.backend.domain.credit;

import com.mongle.backend.global.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

final class CreditRules {
    private CreditRules() {}
    static void positive(long amount) {
        if (amount < 1) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
    }
    static String key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))
            throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        return key;
    }
    // DB 기본 collation과 무관하게 내부 요청 키의 대소문자를 구별한다.
    static String hash(String key) {
        key(key);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static long add(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException e) { throw new BusinessException(CreditErrorCode.CAPACITY_EXCEEDED); }
    }
}
