package com.wuxx.exchangeclear.audit.service;

import com.wuxx.exchangeclear.audit.dto.DownloadAuditMessage;
import com.wuxx.exchangeclear.audit.entity.FileDownloadAudit;
import com.wuxx.exchangeclear.audit.mapper.FileDownloadAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadAuditServiceTest {

    @Mock
    private FileDownloadAuditMapper fileDownloadAuditMapper;

    private DownloadAuditService downloadAuditService;

    @BeforeEach
    void setUp() {
        downloadAuditService = new DownloadAuditService(fileDownloadAuditMapper);
    }

    @Test
    void saveAuditShouldInsertNewMessage() {
        DownloadAuditMessage message = message();

        downloadAuditService.saveAudit(message);

        ArgumentCaptor<FileDownloadAudit> captor = ArgumentCaptor.forClass(FileDownloadAudit.class);
        verify(fileDownloadAuditMapper).insert(captor.capture());
        assertEquals("AUDIT001", captor.getValue().getAuditNo());
        assertEquals("SUCCESS", captor.getValue().getDownloadStatus());
    }

    @Test
    void saveAuditShouldSkipExistingAuditNo() {
        DownloadAuditMessage message = message();
        when(fileDownloadAuditMapper.selectByAuditNo("AUDIT001")).thenReturn(new FileDownloadAudit());

        downloadAuditService.saveAudit(message);

        verify(fileDownloadAuditMapper, never()).insert(any(FileDownloadAudit.class));
    }

    private DownloadAuditMessage message() {
        DownloadAuditMessage message = new DownloadAuditMessage();
        message.setAuditNo("AUDIT001");
        message.setAction("DOWNLOAD_FILE");
        message.setDownloadStatus("SUCCESS");
        message.setFileNo("FILE001");
        message.setMemberId("0001");
        return message;
    }
}
