package com.wuxx.exchangeclear.task.controller;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.task.dto.TaskStatusUpdateRequest;
import com.wuxx.exchangeclear.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Validated
@RestController
@RequestMapping("/internal/tasks")
@RequiredArgsConstructor
public class InternalTaskController {

    private final TaskService taskService;

    @PostMapping("/{taskNo}/status")
    public Result<Void> updateStatus(@PathVariable String taskNo,
                                     @Valid @RequestBody TaskStatusUpdateRequest request) {
        taskService.updateStatus(taskNo, request);
        return Result.success(null);
    }
}
