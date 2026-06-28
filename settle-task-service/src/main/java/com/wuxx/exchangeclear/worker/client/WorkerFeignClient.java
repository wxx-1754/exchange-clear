package com.wuxx.exchangeclear.worker.client;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.task.dto.TaskGenerateResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(name = "settle-worker-service", contextId = "workerFileGenerateFeignClient")
public interface WorkerFeignClient {

    @PostMapping("/internal/workers/files/{taskNo}/generate")
    Result<TaskGenerateResponse> generate(@PathVariable("taskNo") String taskNo);
}
