package com.wuxx.exchangeclear.file.mq;

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
public class FileGenerateTaskLocalProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public String send(String topic, String tag, FileGenerateTaskMessage taskMessage) {
        String destination = topic + ":" + tag;
        Message<FileGenerateTaskMessage> message = MessageBuilder
                .withPayload(taskMessage)
                .setHeader(MessageConst.PROPERTY_KEYS, taskMessage.getTaskNo())
                .build();
        SendResult sendResult = rocketMQTemplate.syncSend(destination, message);
        log.info("[MQ-PRODUCER] send file generate task success, taskNo={}, msgId={}",
                taskMessage.getTaskNo(), sendResult.getMsgId());
        return sendResult.getMsgId();
    }
}
