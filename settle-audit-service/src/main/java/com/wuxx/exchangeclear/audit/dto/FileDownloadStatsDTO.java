package com.wuxx.exchangeclear.audit.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileDownloadStatsDTO {

    private String fileNo;

    private Long successCount;

    private Long failedCount;

    private LocalDateTime lastSuccessTime;

    private String lastClientIp;
}
