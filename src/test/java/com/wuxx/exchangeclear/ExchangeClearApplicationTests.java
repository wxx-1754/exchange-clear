package com.wuxx.exchangeclear;

import com.wuxx.exchangeclear.generator.Md5Util;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExchangeClearApplicationTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldCalculateFileMd5() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.write(file, "exchange-clear".getBytes(StandardCharsets.UTF_8));

        assertEquals("bb35373069e19002b9cffa76bf225f21", Md5Util.calculate(file));
    }
}
