package com.wuxx.exchangeclear.file.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileStatusEventMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void shouldDeserializeLocalMessagePayload() throws Exception {
        String payload = "{\"messageId\":\"MSG20260630192249852140465\","
                + "\"eventType\":\"REVOKED\","
                + "\"fileNo\":\"FILE20260630184834131646320\","
                + "\"settleDate\":\"2026-06-30\","
                + "\"memberId\":\"0004\","
                + "\"fileType\":\"TRADE\","
                + "\"version\":1,"
                + "\"operator\":\"admin\","
                + "\"eventTime\":1782818569852}";

        FileStatusEventMessage message = objectMapper.readValue(payload, FileStatusEventMessage.class);

        assertEquals("MSG20260630192249852140465", message.getMessageId());
        assertEquals("REVOKED", message.getEventType());
        assertEquals("FILE20260630184834131646320", message.getFileNo());
        assertEquals(LocalDate.of(2026, 6, 30), message.getSettleDate());
        assertEquals("0004", message.getMemberId());
        assertEquals("TRADE", message.getFileType());
        assertEquals(1, message.getVersion());
        assertEquals("admin", message.getOperator());
        assertEquals(1782818569852L, message.getEventTime());
    }
}
