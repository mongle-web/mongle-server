package com.mongle.backend.domain.user.withdrawal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component @RequiredArgsConstructor
public class WithdrawalSecrets {
    private final WithdrawalProperties properties;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String identity(String provider, String id) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.identityKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((provider + ":" + id).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("식별값 생성 실패"); }
    }
    public String encrypt(String value) {
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("연결 해제 대상 보호 실패"); }
    }
    public String decrypt(String value) {
        try {
            byte[] all = Base64.getDecoder().decode(value);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(all, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(all, 12, all.length)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IllegalStateException("연결 해제 대상 확인 실패"); }
    }
    private SecretKeySpec key() throws GeneralSecurityException {
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                .digest(properties.encryptionKey().getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
