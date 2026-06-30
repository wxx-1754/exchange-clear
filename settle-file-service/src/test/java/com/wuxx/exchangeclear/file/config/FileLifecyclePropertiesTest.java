package com.wuxx.exchangeclear.file.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileLifecyclePropertiesTest {

    private static final String ROCKETMQ_TOPIC_PATTERN = "^[%|a-zA-Z0-9_-]+$";

    @Test
    void defaultLocalMessageTopicsShouldBeRocketMqCompatible() {
        FileLifecycleProperties.LocalMessage localMessage = new FileLifecycleProperties.LocalMessage();

        assertEquals("file-generate-task", localMessage.getFileGenerateTaskTopic());
        assertTrue(localMessage.getStatusEventTopic().matches(ROCKETMQ_TOPIC_PATTERN));
        assertTrue(localMessage.getFileGenerateTaskTopic().matches(ROCKETMQ_TOPIC_PATTERN));
    }

    @Test
    void applicationLocalMessageTopicDefaultsShouldBeRocketMqCompatible() throws Exception {
        Properties properties = new Properties();
        try (InputStream inputStream = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(inputStream);
            properties.load(inputStream);
        }

        String statusEventTopic = placeholderDefault(
                properties.getProperty("exchange-clear.local-message.status-event-topic"));
        String fileGenerateTaskTopic = placeholderDefault(
                properties.getProperty("exchange-clear.local-message.file-generate-task-topic"));

        assertEquals("file-generate-task", fileGenerateTaskTopic);
        assertTrue(statusEventTopic.matches(ROCKETMQ_TOPIC_PATTERN));
        assertTrue(fileGenerateTaskTopic.matches(ROCKETMQ_TOPIC_PATTERN));
    }

    private String placeholderDefault(String placeholder) {
        int colonIndex = placeholder.lastIndexOf(':');
        int endIndex = placeholder.lastIndexOf('}');
        return placeholder.substring(colonIndex + 1, endIndex);
    }
}
