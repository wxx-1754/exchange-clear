package com.wuxx.exchangeclear.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TaskGenerateResponse {

    private String taskNo;

    private String fileNo;

    private String fileName;

    private Long fileSize;

    private String fileMd5;
}
