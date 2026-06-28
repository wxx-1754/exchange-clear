package com.wuxx.exchangeclear.file.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RevokeFileRequest {

    private String operator;

    @NotBlank(message = "撤销原因不能为空")
    private String reason;
}
