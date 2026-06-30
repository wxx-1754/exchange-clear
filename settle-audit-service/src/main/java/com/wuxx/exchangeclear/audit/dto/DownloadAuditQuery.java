package com.wuxx.exchangeclear.audit.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class DownloadAuditQuery {

    private String memberId;

    private String fileNo;

    private LocalDate settleDate;

    private String fileType;

    private String downloadStatus;

    private String clientIp;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    public int offset() {
        return (normalizedPageNo() - 1) * normalizedPageSize();
    }

    public int normalizedPageNo() {
        return pageNo == null || pageNo < 1 ? 1 : pageNo;
    }

    public int normalizedPageSize() {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }
}
