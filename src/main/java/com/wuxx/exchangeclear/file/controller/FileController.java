package com.wuxx.exchangeclear.file.controller;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.download.service.FileDownloadService;
import com.wuxx.exchangeclear.file.dto.FileChecksumVO;
import com.wuxx.exchangeclear.file.dto.FileVO;
import com.wuxx.exchangeclear.file.service.SettlementFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final SettlementFileService settlementFileService;

    private final FileDownloadService fileDownloadService;

    @GetMapping
    public Result<List<FileVO>> list(@RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDate,
                                     @RequestParam(required = false) String memberId,
                                     @RequestParam(required = false) String fileType) {
        return Result.success(settlementFileService.list(settleDate, memberId, fileType));
    }

    @GetMapping("/{fileNo}/checksum")
    public Result<FileChecksumVO> checksum(@PathVariable String fileNo) {
        return Result.success(settlementFileService.checksum(fileNo));
    }

    @GetMapping("/{fileNo}/download")
    public void download(@PathVariable String fileNo, HttpServletResponse response) {
        fileDownloadService.download(fileNo, response);
    }
}
