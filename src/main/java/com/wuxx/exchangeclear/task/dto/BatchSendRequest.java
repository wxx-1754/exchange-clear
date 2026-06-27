package com.wuxx.exchangeclear.task.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

@Data
public class BatchSendRequest {

    @NotNull(message = "结算日期不能为空")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate settleDate;

    @NotBlank(message = "文件类型不能为空")
    private String fileType;

    private String status = "INIT";
}
