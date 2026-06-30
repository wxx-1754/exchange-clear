package com.wuxx.exchangeclear.audit.consumer;

import com.wuxx.exchangeclear.audit.dto.DownloadAuditMessage;
import com.wuxx.exchangeclear.audit.service.DownloadAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${exchange-clear.mq.topic.download-audit:file-download-audit}",
        consumerGroup = "${exchange-clear.mq.consumer-group.download-audit:exchange-clear-download-audit-consumer-group}",
        selectorExpression = "*",
        consumeThreadNumber = 4,
        maxReconsumeTimes = 3
)
public class DownloadAuditConsumer implements RocketMQListener<DownloadAuditMessage> {

    private final DownloadAuditService downloadAuditService;

    @Override
    public void onMessage(DownloadAuditMessage message) {
        downloadAuditService.saveAudit(message);
        if (message != null) {
            log.info("[AUDIT] consume download audit success, auditNo={}", message.getAuditNo());
        }
    }
}
