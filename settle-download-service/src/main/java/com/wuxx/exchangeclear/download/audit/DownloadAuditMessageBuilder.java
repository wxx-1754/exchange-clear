package com.wuxx.exchangeclear.download.audit;

import com.wuxx.exchangeclear.audit.dto.DownloadAuditMessage;
import com.wuxx.exchangeclear.audit.enums.AuditActionEnum;
import com.wuxx.exchangeclear.audit.enums.DownloadStatusEnum;
import com.wuxx.exchangeclear.common.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class DownloadAuditMessageBuilder {

    private final DownloadAuditProperties auditProperties;

    public DownloadAuditMessage build(AuditContext context,
                                      DownloadStatusEnum status,
                                      String failReason,
                                      long costMs,
                                      Long downloadBytes) {
        LocalDateTime endTime = LocalDateTime.now();
        DownloadAuditMessage message = new DownloadAuditMessage();
        message.setAuditNo(IdGenerator.next("AUDIT"));
        message.setRequestId(context.getRequestId());
        message.setAction(AuditActionEnum.DOWNLOAD_FILE.getCode());
        message.setDownloadStatus(status.getCode());
        message.setFailReason(truncate(failReason, auditProperties.getMaxFailReasonLength()));
        message.setFileNo(context.getFileNo());
        message.setFileName(context.getFileName());
        message.setFileType(context.getFileType());
        message.setSettleDate(context.getSettleDate());
        message.setMemberId(context.getMemberId());
        message.setVersion(context.getVersion());
        message.setClientIp(context.getClientIp());
        message.setUserAgent(truncate(context.getUserAgent(), auditProperties.getMaxUserAgentLength()));
        message.setTokenDigest(context.getTokenDigest());
        message.setFileSize(context.getFileSize());
        message.setDownloadBytes(downloadBytes);
        message.setStartTime(context.getStartTime());
        message.setEndTime(endTime);
        message.setCostMs(costMs);
        message.setEventTime(System.currentTimeMillis());
        return message;
    }

    public String tag(DownloadStatusEnum status) {
        if (DownloadStatusEnum.SUCCESS == status) {
            return "DOWNLOAD_SUCCESS";
        }
        if (DownloadStatusEnum.LIMITED == status) {
            return "DOWNLOAD_LIMITED";
        }
        if (DownloadStatusEnum.DENIED == status) {
            return "DOWNLOAD_DENIED";
        }
        if (DownloadStatusEnum.TOKEN_INVALID == status) {
            return "TOKEN_INVALID";
        }
        if (DownloadStatusEnum.TOKEN_EXPIRED == status) {
            return "TOKEN_EXPIRED";
        }
        return "DOWNLOAD_FAILED";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
