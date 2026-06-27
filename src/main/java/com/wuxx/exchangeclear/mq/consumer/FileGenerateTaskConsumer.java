package com.wuxx.exchangeclear.mq.consumer;

import com.wuxx.exchangeclear.mq.message.FileGenerateTaskMessage;
import com.wuxx.exchangeclear.worker.FileGenerateWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${exchange-clear.mq.topic.file-generate-task:file-generate-task}",
        consumerGroup = "${rocketmq.consumer.group:exchange-clear-file-generate-consumer-group}",
        selectorExpression = "*",
        consumeThreadNumber = 2,
        consumeThreadMax = 2,
        maxReconsumeTimes = 3
)
public class FileGenerateTaskConsumer implements RocketMQListener<FileGenerateTaskMessage> {

    private final FileGenerateWorker fileGenerateWorker;

    @Override
    public void onMessage(FileGenerateTaskMessage message) {
        log.info("[MQ-CONSUMER] receive file generate task, taskNo={}, messageId={}",
                message == null ? null : message.getTaskNo(),
                message == null ? null : message.getMessageId());
        try {
            fileGenerateWorker.handle(message);
            log.info("[MQ-CONSUMER] consume success, taskNo={}", message == null ? null : message.getTaskNo());
        } catch (RuntimeException e) {
            log.error("[MQ-CONSUMER] consume failed, taskNo={}, error={}",
                    message == null ? null : message.getTaskNo(), e.getMessage(), e);
            throw e;
        }
    }
}
