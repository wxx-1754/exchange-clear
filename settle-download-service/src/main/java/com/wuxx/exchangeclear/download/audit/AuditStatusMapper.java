package com.wuxx.exchangeclear.download.audit;

import com.wuxx.exchangeclear.audit.enums.DownloadStatusEnum;
import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.RateLimitException;
import org.springframework.util.StringUtils;

public final class AuditStatusMapper {

    private AuditStatusMapper() {
    }

    public static DownloadStatusEnum from(Throwable throwable) {
        if (throwable instanceof RateLimitException) {
            return DownloadStatusEnum.LIMITED;
        }
        if (throwable instanceof BizException) {
            BizException exception = (BizException) throwable;
            Integer code = exception.getCode();
            if (Integer.valueOf(403004).equals(code)) {
                return DownloadStatusEnum.FILE_REVOKED;
            }
            if (Integer.valueOf(403005).equals(code)) {
                return DownloadStatusEnum.FILE_REISSUED;
            }
            if (Integer.valueOf(403003).equals(code)) {
                return DownloadStatusEnum.FILE_NOT_PUBLISHED;
            }
            return fromMessage(exception.getMessage());
        }
        return DownloadStatusEnum.FAILED;
    }

    private static DownloadStatusEnum fromMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return DownloadStatusEnum.FAILED;
        }
        if (message.contains("文件不存在")) {
            return DownloadStatusEnum.FILE_NOT_FOUND;
        }
        if (message.contains("无权下载")
                || message.contains("会员不匹配")
                || message.contains("客户端 IP 不匹配")) {
            return DownloadStatusEnum.DENIED;
        }
        if (message.contains("Token 不存在") || message.contains("Token 与文件不匹配")) {
            return DownloadStatusEnum.TOKEN_INVALID;
        }
        if (message.contains("Token 已过期")) {
            return DownloadStatusEnum.TOKEN_EXPIRED;
        }
        if (message.contains("Token")) {
            return DownloadStatusEnum.TOKEN_INVALID;
        }
        return DownloadStatusEnum.FAILED;
    }
}
