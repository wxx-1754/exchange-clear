package com.wuxx.exchangeclear.file.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LocalMessage {

    private Long id;

    private String messageId;

    private String topic;

    private String tag;

    private String bizKey;

    private String messageType;

    private String payload;

    private String status;

    private Integer retryCount;

    private Integer maxRetryCount;

    private LocalDateTime nextRetryTime;

    private String errorMessage;

    private LocalDateTime sentTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
