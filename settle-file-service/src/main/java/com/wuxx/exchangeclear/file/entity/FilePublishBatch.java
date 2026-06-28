package com.wuxx.exchangeclear.file.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class FilePublishBatch {

    private Long id;

    private String batchNo;

    private LocalDate settleDate;

    private String fileType;

    private Integer version;

    private String status;

    private Integer totalCount;

    private Integer successCount;

    private Integer failedCount;

    private String operator;

    private String errorMessage;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
