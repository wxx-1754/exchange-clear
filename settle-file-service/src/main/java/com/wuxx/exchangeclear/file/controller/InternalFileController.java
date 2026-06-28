package com.wuxx.exchangeclear.file.controller;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.file.dto.SaveGeneratedFileRequest;
import com.wuxx.exchangeclear.file.service.SettlementFileService;
import lombok.RequiredArgsConstructor;
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

@Validated
@RestController
@RequestMapping("/internal/files")
@RequiredArgsConstructor
public class InternalFileController {

    private final SettlementFileService settlementFileService;

    @PostMapping("/generated")
    public Result<FileMetadataDTO> saveGeneratedFile(@Valid @RequestBody SaveGeneratedFileRequest request) {
        return Result.success(settlementFileService.saveGeneratedFile(request));
    }

    @GetMapping("/{fileNo}")
    public Result<FileMetadataDTO> metadata(@PathVariable String fileNo) {
        return Result.success(settlementFileService.metadata(fileNo));
    }

    @GetMapping("/metadata")
    public Result<FileMetadataDTO> metadata(@RequestParam LocalDate settleDate,
                                            @RequestParam String memberId,
                                            @RequestParam String fileType,
                                            @RequestParam Integer version) {
        return Result.success(settlementFileService.metadata(settleDate, memberId, fileType, version));
    }

    @PostMapping("/{fileNo}/download-count/increase")
    public Result<Void> increaseDownloadCount(@PathVariable String fileNo) {
        settlementFileService.increaseDownloadCount(fileNo);
        return Result.success(null);
    }
}
