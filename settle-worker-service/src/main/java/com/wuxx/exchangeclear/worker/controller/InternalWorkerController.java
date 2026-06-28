package com.wuxx.exchangeclear.worker.controller;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.task.dto.TaskGenerateResponse;
import com.wuxx.exchangeclear.worker.FileGenerateWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/workers/files")
@RequiredArgsConstructor
public class InternalWorkerController {

    private final FileGenerateWorker fileGenerateWorker;

    @PostMapping("/{taskNo}/generate")
    public Result<TaskGenerateResponse> generate(@PathVariable String taskNo) {
        return Result.success(fileGenerateWorker.generateSync(taskNo));
    }
}
