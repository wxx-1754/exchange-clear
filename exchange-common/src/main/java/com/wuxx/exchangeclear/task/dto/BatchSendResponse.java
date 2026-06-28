package com.wuxx.exchangeclear.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BatchSendResponse {

    private Integer totalCount;

    private Integer sentCount;

    private Integer failedCount;
}
