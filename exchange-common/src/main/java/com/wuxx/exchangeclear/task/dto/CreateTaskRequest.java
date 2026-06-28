package com.wuxx.exchangeclear.task.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

@Data
public class CreateTaskRequest {

    @NotNull(message = "结算日期不能为空")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate settleDate;

    @NotBlank(message = "文件类型不能为空")
    private String fileType;

    @NotNull(message = "文件版本不能为空")
    @Min(value = 1, message = "文件版本不能小于1")
    private Integer version = 1;
}
