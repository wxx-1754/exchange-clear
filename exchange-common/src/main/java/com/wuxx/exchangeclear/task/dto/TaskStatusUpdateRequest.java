package com.wuxx.exchangeclear.task.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class TaskStatusUpdateRequest {

    @NotBlank(message = "原任务状态不能为空")
    private String fromStatus;

    @NotBlank(message = "目标任务状态不能为空")
    private String toStatus;

    private String errorMessage;
}
