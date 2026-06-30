package com.wuxx.exchangeclear.audit.service;

import com.wuxx.exchangeclear.audit.dto.AbnormalDownloadStatsDTO;
import com.wuxx.exchangeclear.audit.dto.FileDownloadStatsDTO;
import com.wuxx.exchangeclear.audit.dto.MemberDownloadStatsDTO;
import com.wuxx.exchangeclear.audit.enums.DownloadStatusEnum;
import com.wuxx.exchangeclear.audit.mapper.FileDownloadAuditMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DownloadAuditStatsService {

    private static final int DEFAULT_TOP_LIMIT = 10;

    private final FileDownloadAuditMapper fileDownloadAuditMapper;

    public List<FileDownloadStatsDTO> statFileDownloads(LocalDate settleDate, String fileType, String fileNo) {
        return fileDownloadAuditMapper.statFileDownloads(settleDate, fileType, fileNo);
    }

    public List<MemberDownloadStatsDTO> statMemberDownloads(String memberId,
                                                            LocalDateTime startTime,
                                                            LocalDateTime endTime) {
        return fileDownloadAuditMapper.statMemberDownloads(memberId, startTime, endTime);
    }

    public AbnormalDownloadStatsDTO statAbnormalDownloads(LocalDateTime startTime, LocalDateTime endTime) {
        AbnormalDownloadStatsDTO stats = new AbnormalDownloadStatsDTO();
        stats.setTokenInvalidCount(count(DownloadStatusEnum.TOKEN_INVALID, startTime, endTime));
        stats.setTokenExpiredCount(count(DownloadStatusEnum.TOKEN_EXPIRED, startTime, endTime));
        stats.setDeniedCount(count(DownloadStatusEnum.DENIED, startTime, endTime));
        stats.setLimitedCount(count(DownloadStatusEnum.LIMITED, startTime, endTime));
        stats.setFileNotPublishedCount(count(DownloadStatusEnum.FILE_NOT_PUBLISHED, startTime, endTime));
        stats.setFileRevokedCount(count(DownloadStatusEnum.FILE_REVOKED, startTime, endTime));
        stats.setFileReissuedCount(count(DownloadStatusEnum.FILE_REISSUED, startTime, endTime));
        stats.setTopIps(fileDownloadAuditMapper.topIps(startTime, endTime, DEFAULT_TOP_LIMIT));
        stats.setTopMembers(fileDownloadAuditMapper.topMembers(startTime, endTime, DEFAULT_TOP_LIMIT));
        return stats;
    }

    private long count(DownloadStatusEnum status, LocalDateTime startTime, LocalDateTime endTime) {
        return fileDownloadAuditMapper.countByStatus(status.getCode(), startTime, endTime);
    }
}
