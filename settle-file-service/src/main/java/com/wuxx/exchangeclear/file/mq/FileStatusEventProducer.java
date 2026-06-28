package com.wuxx.exchangeclear.file.mq;

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
public class FileStatusEventProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public String send(String topic, String tag, FileStatusEventMessage event) {
        String destination = topic + ":" + tag;
        Message<FileStatusEventMessage> message = MessageBuilder
                .withPayload(event)
                .setHeader(MessageConst.PROPERTY_KEYS, event.getFileNo())
                .build();
        SendResult sendResult = rocketMQTemplate.syncSend(destination, message);
        log.info("[MQ-PRODUCER] send file status event success, fileNo={}, msgId={}",
                event.getFileNo(), sendResult.getMsgId());
        return sendResult.getMsgId();
    }
}
