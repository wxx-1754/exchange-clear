package com.wuxx.exchangeclear.task.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskGenerateResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeWorkerResponsePayload() throws Exception {
        String payload = "{\"taskNo\":\"TASK001\","
                + "\"fileNo\":\"FILE001\","
                + "\"fileName\":\"trade_0001_20260630.csv\","
                + "\"fileSize\":1024,"
                + "\"fileMd5\":\"abc123\"}";

        TaskGenerateResponse response = objectMapper.readValue(payload, TaskGenerateResponse.class);

        assertEquals("TASK001", response.getTaskNo());
        assertEquals("FILE001", response.getFileNo());
        assertEquals("trade_0001_20260630.csv", response.getFileName());
        assertEquals(1024L, response.getFileSize());
        assertEquals("abc123", response.getFileMd5());
    }
}
