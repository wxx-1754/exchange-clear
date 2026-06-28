package com.wuxx.exchangeclear.task.controller;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.task.dto.BatchGenerateRequest;
import com.wuxx.exchangeclear.task.dto.BatchGenerateResponse;
import com.wuxx.exchangeclear.task.dto.BatchSendRequest;
import com.wuxx.exchangeclear.task.dto.BatchSendResponse;
import com.wuxx.exchangeclear.task.dto.CreateTaskRequest;
import com.wuxx.exchangeclear.task.dto.CreateTaskResponse;
import com.wuxx.exchangeclear.task.dto.TaskGenerateResponse;
import com.wuxx.exchangeclear.task.dto.TaskResendResponse;
import com.wuxx.exchangeclear.task.dto.TaskVO;
import com.wuxx.exchangeclear.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @PostMapping("/create")
    public Result<CreateTaskResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        return Result.success(taskService.createTasks(request));
    }

    @GetMapping
    public Result<List<TaskVO>> list(@RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDate,
                                     @RequestParam(required = false) String status) {
        return Result.success(taskService.list(settleDate, status));
    }

    @PostMapping("/{taskNo}/generate")
    public Result<TaskGenerateResponse> generate(@PathVariable String taskNo) {
        return Result.success(taskService.generate(taskNo));
    }

    @PostMapping("/{taskNo}/generate-sync")
    public Result<TaskGenerateResponse> generateSync(@PathVariable String taskNo) {
        return Result.success(taskService.generate(taskNo));
    }

    @PostMapping("/generate-batch")
    public Result<BatchGenerateResponse> generateBatch(@Valid @RequestBody BatchGenerateRequest request) {
        return Result.success(taskService.generateBatch(request));
    }

    @PostMapping("/send-batch")
    public Result<BatchSendResponse> sendBatch(@Valid @RequestBody BatchSendRequest request) {
        return Result.success(taskService.sendBatch(request));
    }

    @PostMapping("/{taskNo}/resend")
    public Result<TaskResendResponse> resend(@PathVariable String taskNo) {
        return Result.success(taskService.resend(taskNo));
    }
}
