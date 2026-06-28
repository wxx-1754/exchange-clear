package com.wuxx.exchangeclear.task.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class SettleFileTask {

    private Long id;

    private String taskNo;

    private LocalDate settleDate;

    private String memberId;

    private String fileType;

    private Integer version;

    private String status;

    private Integer retryCount;

    private Integer maxRetryCount;

    private String lastMessageId;

    private LocalDateTime lastSendTime;

    private LocalDateTime lastConsumeTime;

    private String errorMessage;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
