package com.wuxx.exchangeclear.file.client;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.task.dto.CreateSingleTaskRequest;
import com.wuxx.exchangeclear.task.dto.CreateSingleTaskResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import javax.validation.Valid;
import java.time.LocalDate;

@FeignClient(name = "settle-task-service", contextId = "fileTaskServiceFeignClient")
public interface TaskServiceFeignClient {

    @PostMapping("/internal/tasks")
    Result<CreateSingleTaskResponse> createSingleTask(@Valid @RequestBody CreateSingleTaskRequest request);

    @GetMapping("/internal/tasks/unfinished-count")
    Result<Integer> countUnfinished(@RequestParam("settleDate") LocalDate settleDate,
                                    @RequestParam(value = "fileType", required = false) String fileType,
                                    @RequestParam(value = "version", required = false) Integer version);

    @GetMapping("/internal/tasks/generated-count")
    Result<Integer> countGenerated(@RequestParam("settleDate") LocalDate settleDate,
                                   @RequestParam(value = "fileType", required = false) String fileType,
                                   @RequestParam(value = "version", required = false) Integer version);
}
