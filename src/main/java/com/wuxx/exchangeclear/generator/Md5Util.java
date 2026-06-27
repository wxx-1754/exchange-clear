package com.wuxx.exchangeclear.generator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Md5Util {

    private static final int BUFFER_SIZE = 8192;

    private Md5Util() {
    }

    public static String calculate(Path path) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
            return toHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算文件MD5失败", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            String hex = Integer.toHexString(item & 0xff);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }
}
