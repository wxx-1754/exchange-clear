package com.wuxx.exchangeclear.audit.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MemberDownloadStatsDTO {

    private String memberId;

    private Long totalCount;

    private Long successCount;

    private Long failedCount;

    private LocalDateTime lastDownloadTime;
}
