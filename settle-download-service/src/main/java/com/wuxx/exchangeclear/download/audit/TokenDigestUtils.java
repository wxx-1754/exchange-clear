package com.wuxx.exchangeclear.download.audit;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class TokenDigestUtils {

    private TokenDigestUtils() {
    }

    public static String sha256(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Token摘要生成失败", e);
        }
    }
}
