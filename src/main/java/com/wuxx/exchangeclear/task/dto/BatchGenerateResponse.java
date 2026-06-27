package com.wuxx.exchangeclear.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BatchGenerateResponse {

    private Integer successCount;

    private Integer failedCount;
}
