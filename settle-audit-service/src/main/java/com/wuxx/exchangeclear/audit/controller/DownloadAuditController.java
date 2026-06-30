package com.wuxx.exchangeclear.audit.controller;

import com.wuxx.exchangeclear.audit.dto.DownloadAuditDTO;
import com.wuxx.exchangeclear.audit.dto.DownloadAuditQuery;
import com.wuxx.exchangeclear.audit.dto.PageResult;
import com.wuxx.exchangeclear.audit.service.DownloadAuditService;
import com.wuxx.exchangeclear.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Validated
@RestController
@RequestMapping("/api/audits")
@RequiredArgsConstructor
public class DownloadAuditController {

    private final DownloadAuditService downloadAuditService;

    @GetMapping("/downloads")
    public Result<PageResult<DownloadAuditDTO>> list(@RequestParam(required = false) String memberId,
                                                     @RequestParam(required = false) String fileNo,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDate,
                                                     @RequestParam(required = false) String fileType,
                                                     @RequestParam(required = false) String downloadStatus,
                                                     @RequestParam(required = false) String clientIp,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
                                                     @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                                     @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return Result.success(downloadAuditService.list(
                query(memberId, fileNo, settleDate, fileType, downloadStatus, clientIp,
                        startTime, endTime, pageNo, pageSize)));
    }

    @GetMapping("/downloads/{auditNo}")
    public Result<DownloadAuditDTO> detail(@PathVariable String auditNo) {
        return Result.success(downloadAuditService.detail(auditNo));
    }

    @GetMapping("/files/{fileNo}/downloads")
    public Result<PageResult<DownloadAuditDTO>> listByFile(@PathVariable String fileNo,
                                                           @RequestParam(required = false)
                                                           @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                                           @RequestParam(required = false)
                                                           @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
                                                           @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                                           @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return Result.success(downloadAuditService.listByFile(
                fileNo, query(null, null, null, null, null, null, startTime, endTime, pageNo, pageSize)));
    }

    @GetMapping("/members/{memberId}/downloads")
    public Result<PageResult<DownloadAuditDTO>> listByMember(@PathVariable String memberId,
                                                             @RequestParam(required = false)
                                                             @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                                             @RequestParam(required = false)
                                                             @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
                                                             @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                                             @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        return Result.success(downloadAuditService.listByMember(
                memberId, query(null, null, null, null, null, null, startTime, endTime, pageNo, pageSize)));
    }

    private DownloadAuditQuery query(String memberId,
                                     String fileNo,
                                     LocalDate settleDate,
                                     String fileType,
                                     String downloadStatus,
                                     String clientIp,
                                     LocalDateTime startTime,
                                     LocalDateTime endTime,
                                     Integer pageNo,
                                     Integer pageSize) {
        DownloadAuditQuery query = new DownloadAuditQuery();
        query.setMemberId(memberId);
        query.setFileNo(fileNo);
        query.setSettleDate(settleDate);
        query.setFileType(fileType);
        query.setDownloadStatus(downloadStatus);
        query.setClientIp(clientIp);
        query.setStartTime(startTime);
        query.setEndTime(endTime);
        query.setPageNo(pageNo);
        query.setPageSize(pageSize);
        return query;
    }
}
