package com.wuxx.exchangeclear.audit.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class DownloadAuditDTO {

    private String auditNo;

    private String requestId;

    private String action;

    private String downloadStatus;

    private String failReason;

    private String fileNo;

    private String fileName;

    private String fileType;

    private LocalDate settleDate;

    private String memberId;

    private Integer version;

    private String clientIp;

    private String userAgent;

    private String tokenDigest;

    private Long fileSize;

    private Long downloadBytes;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long costMs;

    private LocalDateTime createdAt;
}
