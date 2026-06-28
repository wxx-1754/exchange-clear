package com.wuxx.exchangeclear.file.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

@Data
public class PublishFileRequest {

    @NotNull(message = "结算日期不能为空")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate settleDate;

    private String fileType;

    @Min(value = 1, message = "文件版本不能小于1")
    private Integer version;

    private String operator;
}
