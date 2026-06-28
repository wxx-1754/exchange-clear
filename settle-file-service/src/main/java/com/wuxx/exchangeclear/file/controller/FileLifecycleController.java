package com.wuxx.exchangeclear.file.controller;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.file.dto.FileStatusLogVO;
import com.wuxx.exchangeclear.file.dto.PublishFileRequest;
import com.wuxx.exchangeclear.file.dto.PublishFileResponse;
import com.wuxx.exchangeclear.file.dto.ReissueFileRequest;
import com.wuxx.exchangeclear.file.dto.ReissueFileResponse;
import com.wuxx.exchangeclear.file.dto.RevokeFileRequest;
import com.wuxx.exchangeclear.file.dto.RevokeFileResponse;
import com.wuxx.exchangeclear.file.entity.FilePublishBatch;
import com.wuxx.exchangeclear.file.service.FileLifecycleService;
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
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileLifecycleController {

    private final FileLifecycleService fileLifecycleService;

    @PostMapping("/publish")
    public Result<PublishFileResponse> publish(@Valid @RequestBody PublishFileRequest request) {
        return Result.success(fileLifecycleService.publish(request));
    }

    @GetMapping("/publish-batches")
    public Result<List<FilePublishBatch>> listPublishBatches(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDate) {
        return Result.success(fileLifecycleService.listPublishBatches(settleDate));
    }

    @PostMapping("/{fileNo}/revoke")
    public Result<RevokeFileResponse> revoke(@PathVariable String fileNo,
                                             @Valid @RequestBody RevokeFileRequest request) {
        return Result.success(fileLifecycleService.revoke(fileNo, request));
    }

    @PostMapping("/{fileNo}/reissue")
    public Result<ReissueFileResponse> reissue(@PathVariable String fileNo,
                                               @Valid @RequestBody ReissueFileRequest request) {
        return Result.success(fileLifecycleService.reissue(fileNo, request));
    }

    @GetMapping("/{fileNo}/status-logs")
    public Result<List<FileStatusLogVO>> listStatusLogs(@PathVariable String fileNo) {
        return Result.success(fileLifecycleService.listStatusLogs(fileNo));
    }
}
