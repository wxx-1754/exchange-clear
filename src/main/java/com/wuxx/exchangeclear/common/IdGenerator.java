package com.wuxx.exchangeclear.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

public final class IdGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private IdGenerator() {
    }

    public static String next(String prefix) {
        int random = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return prefix + LocalDateTime.now().format(FORMATTER) + random;
    }
}
