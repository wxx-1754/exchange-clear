package com.wuxx.exchangeclear.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TaskResendResponse {

    private String taskNo;

    private String status;
}
