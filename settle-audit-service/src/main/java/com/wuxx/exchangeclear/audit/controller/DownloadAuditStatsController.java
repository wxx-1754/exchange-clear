package com.wuxx.exchangeclear.audit.controller;

import com.wuxx.exchangeclear.audit.dto.AbnormalDownloadStatsDTO;
import com.wuxx.exchangeclear.audit.dto.FileDownloadStatsDTO;
import com.wuxx.exchangeclear.audit.dto.MemberDownloadStatsDTO;
import com.wuxx.exchangeclear.audit.service.DownloadAuditStatsService;
import com.wuxx.exchangeclear.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/audits/stats")
@RequiredArgsConstructor
public class DownloadAuditStatsController {

    private final DownloadAuditStatsService downloadAuditStatsService;

    @GetMapping("/file-download")
    public Result<List<FileDownloadStatsDTO>> statFileDownloads(@RequestParam(required = false)
                                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                                LocalDate settleDate,
                                                                @RequestParam(required = false) String fileType,
                                                                @RequestParam(required = false) String fileNo) {
        return Result.success(downloadAuditStatsService.statFileDownloads(settleDate, fileType, fileNo));
    }

    @GetMapping("/member-download")
    public Result<List<MemberDownloadStatsDTO>> statMemberDownloads(@RequestParam(required = false) String memberId,
                                                                    @RequestParam(required = false)
                                                                    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                                                    LocalDateTime startTime,
                                                                    @RequestParam(required = false)
                                                                    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                                                    LocalDateTime endTime) {
        return Result.success(downloadAuditStatsService.statMemberDownloads(memberId, startTime, endTime));
    }

    @GetMapping("/abnormal")
    public Result<AbnormalDownloadStatsDTO> statAbnormalDownloads(@RequestParam(required = false)
                                                                  @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                                                  LocalDateTime startTime,
                                                                  @RequestParam(required = false)
                                                                  @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                                                  LocalDateTime endTime) {
        return Result.success(downloadAuditStatsService.statAbnormalDownloads(startTime, endTime));
    }
}
