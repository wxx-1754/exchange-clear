package com.wuxx.exchangeclear.audit.service;

import com.wuxx.exchangeclear.audit.dto.DownloadAuditDTO;
import com.wuxx.exchangeclear.audit.dto.DownloadAuditMessage;
import com.wuxx.exchangeclear.audit.dto.DownloadAuditQuery;
import com.wuxx.exchangeclear.audit.dto.PageResult;
import com.wuxx.exchangeclear.audit.entity.FileDownloadAudit;
import com.wuxx.exchangeclear.audit.mapper.FileDownloadAuditMapper;
import com.wuxx.exchangeclear.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadAuditService {

    private static final int MAX_FAIL_REASON_LENGTH = 1000;

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final FileDownloadAuditMapper fileDownloadAuditMapper;

    @Transactional(rollbackFor = Exception.class)
    public void saveAudit(DownloadAuditMessage message) {
        if (message == null || !StringUtils.hasText(message.getAuditNo())) {
            log.warn("[AUDIT] download audit message is invalid, message={}", message);
            return;
        }
        FileDownloadAudit exists = fileDownloadAuditMapper.selectByAuditNo(message.getAuditNo());
        if (exists != null) {
            log.info("[AUDIT] download audit already exists, auditNo={}", message.getAuditNo());
            return;
        }

        try {
            fileDownloadAuditMapper.insert(convert(message));
        } catch (DuplicateKeyException e) {
            log.info("[AUDIT] duplicate download audit message, auditNo={}", message.getAuditNo());
        }
    }

    public PageResult<DownloadAuditDTO> list(DownloadAuditQuery query) {
        DownloadAuditQuery actualQuery = query == null ? new DownloadAuditQuery() : query;
        long total = fileDownloadAuditMapper.countByQuery(actualQuery);
        List<DownloadAuditDTO> records = fileDownloadAuditMapper
                .listByQuery(actualQuery, actualQuery.offset(), actualQuery.normalizedPageSize())
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return new PageResult<>(total, records);
    }

    public DownloadAuditDTO detail(String auditNo) {
        FileDownloadAudit audit = fileDownloadAuditMapper.selectByAuditNo(auditNo);
        if (audit == null) {
            throw new BizException("审计记录不存在：" + auditNo);
        }
        return toDTO(audit);
    }

    public PageResult<DownloadAuditDTO> listByFile(String fileNo, DownloadAuditQuery query) {
        DownloadAuditQuery actualQuery = query == null ? new DownloadAuditQuery() : query;
        actualQuery.setFileNo(fileNo);
        return list(actualQuery);
    }

    public PageResult<DownloadAuditDTO> listByMember(String memberId, DownloadAuditQuery query) {
        DownloadAuditQuery actualQuery = query == null ? new DownloadAuditQuery() : query;
        actualQuery.setMemberId(memberId);
        return list(actualQuery);
    }

    private FileDownloadAudit convert(DownloadAuditMessage message) {
        FileDownloadAudit audit = new FileDownloadAudit();
        audit.setAuditNo(message.getAuditNo());
        audit.setRequestId(message.getRequestId());
        audit.setAction(message.getAction());
        audit.setDownloadStatus(message.getDownloadStatus());
        audit.setFailReason(truncate(message.getFailReason(), MAX_FAIL_REASON_LENGTH));
        audit.setFileNo(message.getFileNo());
        audit.setFileName(message.getFileName());
        audit.setFileType(message.getFileType());
        audit.setSettleDate(message.getSettleDate());
        audit.setMemberId(message.getMemberId());
        audit.setVersion(message.getVersion());
        audit.setClientIp(message.getClientIp());
        audit.setUserAgent(truncate(message.getUserAgent(), MAX_USER_AGENT_LENGTH));
        audit.setTokenDigest(message.getTokenDigest());
        audit.setFileSize(message.getFileSize());
        audit.setDownloadBytes(message.getDownloadBytes());
        audit.setStartTime(message.getStartTime());
        audit.setEndTime(message.getEndTime());
        audit.setCostMs(message.getCostMs());
        return audit;
    }

    private DownloadAuditDTO toDTO(FileDownloadAudit audit) {
        DownloadAuditDTO dto = new DownloadAuditDTO();
        dto.setAuditNo(audit.getAuditNo());
        dto.setRequestId(audit.getRequestId());
        dto.setAction(audit.getAction());
        dto.setDownloadStatus(audit.getDownloadStatus());
        dto.setFailReason(audit.getFailReason());
        dto.setFileNo(audit.getFileNo());
        dto.setFileName(audit.getFileName());
        dto.setFileType(audit.getFileType());
        dto.setSettleDate(audit.getSettleDate());
        dto.setMemberId(audit.getMemberId());
        dto.setVersion(audit.getVersion());
        dto.setClientIp(audit.getClientIp());
        dto.setUserAgent(audit.getUserAgent());
        dto.setTokenDigest(audit.getTokenDigest());
        dto.setFileSize(audit.getFileSize());
        dto.setDownloadBytes(audit.getDownloadBytes());
        dto.setStartTime(audit.getStartTime());
        dto.setEndTime(audit.getEndTime());
        dto.setCostMs(audit.getCostMs());
        dto.setCreatedAt(audit.getCreatedAt());
        return dto;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
