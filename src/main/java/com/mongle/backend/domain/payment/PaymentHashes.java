package com.mongle.backend.domain.payment;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
final class PaymentHashes {
    private PaymentHashes() {}
    static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    static boolean equal(String a,String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }
}
