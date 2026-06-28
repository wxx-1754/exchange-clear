package com.wuxx.exchangeclear.file.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ReissueFileRequest {

    private String operator;

    @NotBlank(message = "重发原因不能为空")
    private String reason;
}
