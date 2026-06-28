package com.wuxx.exchangeclear.file.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class SettleFile {

    private Long id;

    private String fileNo;

    private String taskNo;

    private LocalDate settleDate;

    private String memberId;

    private String fileType;

    private String fileName;

    private Long fileSize;

    private String fileMd5;

    private String storageBucket;

    private String storagePath;

    private Integer version;

    private String status;

    private LocalDateTime publishTime;

    private Long downloadCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
