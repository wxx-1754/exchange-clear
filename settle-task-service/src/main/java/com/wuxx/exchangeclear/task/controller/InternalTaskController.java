package com.wuxx.exchangeclear.task.controller;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.task.dto.CreateSingleTaskRequest;
import com.wuxx.exchangeclear.task.dto.CreateSingleTaskResponse;
import com.wuxx.exchangeclear.task.dto.TaskStatusUpdateRequest;
import com.wuxx.exchangeclear.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;

@Validated
@RestController
@RequestMapping("/internal/tasks")
@RequiredArgsConstructor
public class InternalTaskController {

    private final TaskService taskService;

    @PostMapping
    public Result<CreateSingleTaskResponse> createSingleTask(@Valid @RequestBody CreateSingleTaskRequest request) {
        return Result.success(taskService.createSingleTask(request));
    }

    @GetMapping("/unfinished-count")
    public Result<Integer> countUnfinished(@RequestParam LocalDate settleDate,
                                           @RequestParam(required = false) String fileType,
                                           @RequestParam(required = false) Integer version) {
        return Result.success(taskService.countUnfinished(settleDate, fileType, version));
    }

    @GetMapping("/generated-count")
    public Result<Integer> countGenerated(@RequestParam LocalDate settleDate,
                                          @RequestParam(required = false) String fileType,
                                          @RequestParam(required = false) Integer version) {
        return Result.success(taskService.countGenerated(settleDate, fileType, version));
    }

    @PostMapping("/{taskNo}/status")
    public Result<Void> updateStatus(@PathVariable String taskNo,
                                     @Valid @RequestBody TaskStatusUpdateRequest request) {
        taskService.updateStatus(taskNo, request);
        return Result.success(null);
    }
}
