package com.wuxx.exchangeclear.file.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FileChecksumVO {

    private String fileNo;

    private String fileName;

    private Long fileSize;

    private String fileMd5;
}
