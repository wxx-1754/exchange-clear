package com.wuxx.exchangeclear.mq.producer;

import com.wuxx.exchangeclear.mq.config.ExchangeClearMqProperties;
import com.wuxx.exchangeclear.mq.message.FileGenerateTaskMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileGenerateTaskProducer {

    private final RocketMQTemplate rocketMQTemplate;

    private final ExchangeClearMqProperties mqProperties;

    public String send(FileGenerateTaskMessage message) {
        String destination = mqProperties.buildFileGenerateDestination(message.getFileType());
        Message<FileGenerateTaskMessage> mqMessage = MessageBuilder
                .withPayload(message)
                .setHeader(MessageConst.PROPERTY_KEYS, message.getTaskNo())
                .build();

        log.info("[MQ-PRODUCER] send file generate task start, taskNo={}", message.getTaskNo());
        SendResult sendResult = rocketMQTemplate.syncSend(destination, mqMessage);
        log.info("[MQ-PRODUCER] send file generate task success, taskNo={}, msgId={}",
                message.getTaskNo(), sendResult.getMsgId());
        return sendResult.getMsgId();
    }
}
