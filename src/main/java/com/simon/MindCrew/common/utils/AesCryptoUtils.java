package com.simon.MindCrew.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 对称加密工具。
 *
 * 用途：加密存储敏感配置（API Key、SecretKey 等）。
 *
 * 配置 application.yml:
 *   crypto:
 *     master-key: please-change-me-in-production   # 32+ 字符，生产用 KMS
 *
 * 注意：master-key 用 SHA-256 派生为 256-bit key，所以原文可以是任意长度（≥1 字符）。
 *      但生产环境必须改成随机长串，并通过环境变量注入。
 */
@Slf4j
@Component
public class AesCryptoUtils {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;

    @Value("${crypto.master-key:please-change-me-in-production}")
    private String masterKey;

    /** 加密。输入 null/空 直接返回 null。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, derivedKey(), new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            // 拼接 iv + ciphertext，整体 base64
            byte[] full = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, full, 0, iv.length);
            System.arraycopy(ciphertext, 0, full, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(full);
        } catch (Exception e) {
            throw new RuntimeException("加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密。失败返回原值（兼容历史明文数据 / 跨环境）。 */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return encrypted;
        try {
            byte[] full = Base64.getDecoder().decode(encrypted);
            if (full.length < GCM_IV_LEN + 16) return encrypted; // 不像 GCM 输出，可能是明文
            byte[] iv = new byte[GCM_IV_LEN];
            byte[] ct = new byte[full.length - GCM_IV_LEN];
            System.arraycopy(full, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(full, GCM_IV_LEN, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, derivedKey(), new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("解密失败，返回原值: {}", e.getMessage());
            return encrypted;
        }
    }

    /** 脱敏：用于回传给前端展示，只露前 4 后 4 */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) return "";
        if (secret.length() <= 8) return "****";
        return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 4);
    }

    private SecretKey derivedKey() throws Exception {
        // SHA-256(masterKey) → 32 字节 = AES-256 key
        byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }
}
