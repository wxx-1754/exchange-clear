package com.wuxx.exchangeclear.file.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class FileMetadataDTO {

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

    private Long downloadCount;
}
