package com.wuxx.exchangeclear.file.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

@Data
public class SaveGeneratedFileRequest {

    @NotBlank(message = "任务编号不能为空")
    private String taskNo;

    @NotNull(message = "结算日期不能为空")
    private LocalDate settleDate;

    @NotBlank(message = "会员编号不能为空")
    private String memberId;

    @NotBlank(message = "文件类型不能为空")
    private String fileType;

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotNull(message = "文件大小不能为空")
    private Long fileSize;

    @NotBlank(message = "文件MD5不能为空")
    private String fileMd5;

    @NotBlank(message = "存储桶不能为空")
    private String storageBucket;

    @NotBlank(message = "存储路径不能为空")
    private String storagePath;

    @NotNull(message = "文件版本不能为空")
    private Integer version;
}
