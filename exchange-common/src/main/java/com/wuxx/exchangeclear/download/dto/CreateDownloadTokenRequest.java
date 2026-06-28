package com.wuxx.exchangeclear.download.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class CreateDownloadTokenRequest {

    @NotBlank(message = "文件编号不能为空")
    private String fileNo;
}
