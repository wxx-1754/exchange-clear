package com.wuxx.exchangeclear.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

@Data
@AllArgsConstructor
public class CreateTaskResponse {

    private LocalDate settleDate;

    private String fileType;

    private Integer createdCount;

    private Integer sentCount;

    private Integer sendFailedCount;

    private Integer existsCount;
}
