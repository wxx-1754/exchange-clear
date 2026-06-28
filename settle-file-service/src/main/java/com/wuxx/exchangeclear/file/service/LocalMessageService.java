package com.wuxx.exchangeclear.file.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxx.exchangeclear.common.IdGenerator;
import com.wuxx.exchangeclear.file.config.FileLifecycleProperties;
import com.wuxx.exchangeclear.file.entity.LocalMessage;
import com.wuxx.exchangeclear.file.mapper.LocalMessageMapper;
import com.wuxx.exchangeclear.file.mq.FileGenerateTaskLocalProducer;
import com.wuxx.exchangeclear.file.mq.FileStatusEventMessage;
import com.wuxx.exchangeclear.file.mq.FileStatusEventProducer;
import com.wuxx.exchangeclear.mq.message.FileGenerateTaskMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalMessageService {

    private final LocalMessageMapper localMessageMapper;

    private final FileStatusEventProducer fileStatusEventProducer;

    private final FileGenerateTaskLocalProducer fileGenerateTaskLocalProducer;

    private final FileLifecycleProperties fileLifecycleProperties;

    private final ObjectMapper objectMapper;

    public String saveStatusEvent(FileStatusEventMessage event, String messageType) {
        String messageId = event.getMessageId() == null ? IdGenerator.next("MSG") : event.getMessageId();
        event.setMessageId(messageId);

        LocalMessage message = new LocalMessage();
        message.setMessageId(messageId);
        message.setTopic(fileLifecycleProperties.getLocalMessage().getStatusEventTopic());
        message.setTag(event.getEventType());
        message.setBizKey(event.getFileNo());
        message.setMessageType(messageType);
        message.setPayload(toJson(event));
        message.setStatus("INIT");
        message.setRetryCount(0);
        message.setMaxRetryCount(fileLifecycleProperties.getLocalMessage().getMaxRetryCount());
        localMessageMapper.insert(message);
        return messageId;
    }

    public String saveGenerateTask(FileGenerateTaskMessage taskMessage) {
        String messageId = taskMessage.getMessageId() == null ? IdGenerator.next("MSG") : taskMessage.getMessageId();
        taskMessage.setMessageId(messageId);

        LocalMessage message = new LocalMessage();
        message.setMessageId(messageId);
        message.setTopic(fileLifecycleProperties.getLocalMessage().getFileGenerateTaskTopic());
        message.setTag(taskMessage.getFileType());
        message.setBizKey(taskMessage.getTaskNo());
        message.setMessageType("FILE_GENERATE_TASK");
        message.setPayload(toJson(taskMessage));
        message.setStatus("INIT");
        message.setRetryCount(0);
        message.setMaxRetryCount(fileLifecycleProperties.getLocalMessage().getMaxRetryCount());
        localMessageMapper.insert(message);
        return messageId;
    }

    public void sendMessages(List<String> messageIds) {
        for (String messageId : messageIds) {
            LocalMessage message = localMessageMapper.selectByMessageId(messageId);
            if (message != null) {
                sendMessage(message);
            }
        }
    }

    public void retryDueMessages() {
        List<LocalMessage> messages = localMessageMapper.selectRetryMessages(
                LocalDateTime.now(), fileLifecycleProperties.getLocalMessage().getBatchSize());
        for (LocalMessage message : messages) {
            sendMessage(message);
        }
    }

    private void sendMessage(LocalMessage message) {
        try {
            if ("FILE_GENERATE_TASK".equals(message.getMessageType())) {
                FileGenerateTaskMessage taskMessage = objectMapper.readValue(message.getPayload(), FileGenerateTaskMessage.class);
                fileGenerateTaskLocalProducer.send(message.getTopic(), message.getTag(), taskMessage);
            } else {
                FileStatusEventMessage event = objectMapper.readValue(message.getPayload(), FileStatusEventMessage.class);
                fileStatusEventProducer.send(message.getTopic(), message.getTag(), event);
            }
            localMessageMapper.markSent(message.getMessageId());
        } catch (Exception e) {
            String errorMessage = truncate(rootMessage(e));
            LocalDateTime nextRetryTime = LocalDateTime.now()
                    .plusSeconds(fileLifecycleProperties.getLocalMessage().getRetryIntervalSeconds());
            localMessageMapper.markFailed(message.getMessageId(), errorMessage, nextRetryTime);
            log.error("[MQ-PRODUCER] send local message failed, messageId={}, error={}",
                    message.getMessageId(), errorMessage, e);
        }
    }

    private String toJson(FileStatusEventMessage event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("本地消息序列化失败", e);
        }
    }

    private String toJson(FileGenerateTaskMessage taskMessage) {
        try {
            return objectMapper.writeValueAsString(taskMessage);
        } catch (Exception e) {
            throw new IllegalStateException("本地消息序列化失败", e);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return "未知异常";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
