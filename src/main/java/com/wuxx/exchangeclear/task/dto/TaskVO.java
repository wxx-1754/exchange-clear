package com.wuxx.exchangeclear.task.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TaskVO {

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
}
