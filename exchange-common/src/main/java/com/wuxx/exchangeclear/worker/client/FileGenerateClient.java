package com.wuxx.exchangeclear.worker.client;

import com.wuxx.exchangeclear.task.dto.TaskGenerateResponse;

public interface FileGenerateClient {

    TaskGenerateResponse generate(String taskNo);
}
