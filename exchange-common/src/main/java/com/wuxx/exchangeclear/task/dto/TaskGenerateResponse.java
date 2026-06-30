package com.wuxx.exchangeclear.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskGenerateResponse {

    private String taskNo;

    private String fileNo;

    private String fileName;

    private Long fileSize;

    private String fileMd5;
}
