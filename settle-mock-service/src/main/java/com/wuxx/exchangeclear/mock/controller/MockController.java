package com.wuxx.exchangeclear.mock.controller;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.mock.dto.MockInitRequest;
import com.wuxx.exchangeclear.mock.dto.MockInitResponse;
import com.wuxx.exchangeclear.mock.service.MockDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/mock")
@RequiredArgsConstructor
public class MockController {

    private final MockDataService mockDataService;

    @PostMapping("/init")
    public Result<MockInitResponse> init(@Valid @RequestBody MockInitRequest request) {
        return Result.success(mockDataService.init(request));
    }
}
