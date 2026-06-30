package com.wuxx.exchangeclear.download.audit;

import com.wuxx.exchangeclear.audit.dto.DownloadAuditMessage;
import com.wuxx.exchangeclear.audit.enums.DownloadStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadAuditProducer {

    private final RocketMQTemplate rocketMQTemplate;

    private final DownloadAuditProperties auditProperties;

    private final DownloadAuditMessageBuilder messageBuilder;

    @Value("${exchange-clear.mq.topic.download-audit:file-download-audit}")
    private String topic;

    public void send(AuditContext context,
                     DownloadStatusEnum status,
                     String failReason,
                     long costMs,
                     Long downloadBytes) {
        if (!auditProperties.isEnabled()) {
            return;
        }
        DownloadAuditMessage auditMessage = messageBuilder.build(context, status, failReason, costMs, downloadBytes);
        try {
            String destination = topic + ":" + messageBuilder.tag(status);
            Message<DownloadAuditMessage> message = MessageBuilder
                    .withPayload(auditMessage)
                    .setHeader(MessageConst.PROPERTY_KEYS, auditMessage.getAuditNo())
                    .build();
            rocketMQTemplate.asyncSend(destination, message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.info("[AUDIT] send download audit success, auditNo={}, status={}, msgId={}",
                            auditMessage.getAuditNo(), auditMessage.getDownloadStatus(), sendResult.getMsgId());
                }

                @Override
                public void onException(Throwable throwable) {
                    log.error("[AUDIT] send download audit failed, auditNo={}, fileNo={}, status={}",
                            auditMessage.getAuditNo(), auditMessage.getFileNo(),
                            auditMessage.getDownloadStatus(), throwable);
                }
            });
        } catch (Exception e) {
            log.error("[AUDIT] send download audit failed, auditNo={}, fileNo={}, status={}",
                    auditMessage.getAuditNo(), auditMessage.getFileNo(), auditMessage.getDownloadStatus(), e);
        }
    }
}
