package com.wuxx.exchangeclear.file.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class FileStatusLog {

    private Long id;

    private String fileNo;

    private LocalDate settleDate;

    private String memberId;

    private String fileType;

    private Integer version;

    private String beforeStatus;

    private String afterStatus;

    private String operationType;

    private String batchNo;

    private String operator;

    private String reason;

    private LocalDateTime createdAt;
}
