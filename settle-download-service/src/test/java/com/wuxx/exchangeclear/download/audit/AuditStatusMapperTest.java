package com.wuxx.exchangeclear.download.audit;

import com.wuxx.exchangeclear.audit.enums.DownloadStatusEnum;
import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.RateLimitException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditStatusMapperTest {

    @Test
    void shouldMapRateLimitToLimited() {
        assertEquals(DownloadStatusEnum.LIMITED,
                AuditStatusMapper.from(new RateLimitException("请求过于频繁")));
    }

    @Test
    void shouldMapFileStatusCodes() {
        assertEquals(DownloadStatusEnum.FILE_NOT_PUBLISHED,
                AuditStatusMapper.from(new BizException(403003, "文件尚未发布或已失效，禁止下载")));
        assertEquals(DownloadStatusEnum.FILE_REVOKED,
                AuditStatusMapper.from(new BizException(403004, "文件已撤销，禁止下载")));
        assertEquals(DownloadStatusEnum.FILE_REISSUED,
                AuditStatusMapper.from(new BizException(403005, "文件已重发，请下载新版本文件")));
    }

    @Test
    void shouldMapTokenAndPermissionMessages() {
        assertEquals(DownloadStatusEnum.TOKEN_INVALID,
                AuditStatusMapper.from(new BizException("下载 Token 不存在或已过期")));
        assertEquals(DownloadStatusEnum.TOKEN_EXPIRED,
                AuditStatusMapper.from(new BizException("下载 Token 已过期")));
        assertEquals(DownloadStatusEnum.DENIED,
                AuditStatusMapper.from(new BizException("无权下载该文件")));
        assertEquals(DownloadStatusEnum.FILE_NOT_FOUND,
                AuditStatusMapper.from(new BizException("文件不存在")));
    }
}
