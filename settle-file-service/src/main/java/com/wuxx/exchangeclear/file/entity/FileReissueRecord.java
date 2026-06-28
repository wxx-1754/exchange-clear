package com.wuxx.exchangeclear.file.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class FileReissueRecord {

    private Long id;

    private String reissueNo;

    private String oldFileNo;

    private String newFileNo;

    private LocalDate settleDate;

    private String memberId;

    private String fileType;

    private Integer oldVersion;

    private Integer newVersion;

    private String status;

    private String reason;

    private String operator;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
