package com.wuxx.exchangeclear.file.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class FileVO {

    private String fileNo;

    private LocalDate settleDate;

    private String memberId;

    private String fileType;

    private String fileName;

    private Long fileSize;

    private String fileMd5;

    private String status;

    private Integer version;

    private Long downloadCount;
}
