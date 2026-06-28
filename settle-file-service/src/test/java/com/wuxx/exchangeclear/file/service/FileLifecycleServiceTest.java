package com.wuxx.exchangeclear.file.service;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.enums.FileStatusEnum;
import com.wuxx.exchangeclear.file.cache.FileCacheService;
import com.wuxx.exchangeclear.file.client.TaskMetadataClient;
import com.wuxx.exchangeclear.file.config.FileLifecycleProperties;
import com.wuxx.exchangeclear.file.dto.PublishFileRequest;
import com.wuxx.exchangeclear.file.dto.RevokeFileRequest;
import com.wuxx.exchangeclear.file.entity.SettleFile;
import com.wuxx.exchangeclear.file.lock.FileOperationLockService;
import com.wuxx.exchangeclear.file.mapper.FilePublishBatchMapper;
import com.wuxx.exchangeclear.file.mapper.FileReissueRecordMapper;
import com.wuxx.exchangeclear.file.mapper.FileStatusLogMapper;
import com.wuxx.exchangeclear.file.mapper.SettleFileMapper;
import com.wuxx.exchangeclear.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.LocalDate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileLifecycleServiceTest {

    @Mock
    private SettleFileMapper settleFileMapper;

    @Mock
    private FilePublishBatchMapper filePublishBatchMapper;

    @Mock
    private FileStatusLogMapper fileStatusLogMapper;

    @Mock
    private FileReissueRecordMapper fileReissueRecordMapper;

    @Mock
    private FileOperationLockService fileOperationLockService;

    @Mock
    private FileCacheService fileCacheService;

    @Mock
    private LocalMessageService localMessageService;

    @Mock
    private TaskMetadataClient taskMetadataClient;

    @Mock
    private ObjectStorageService objectStorageService;

    private FileLifecycleService fileLifecycleService;

    @BeforeEach
    void setUp() {
        FileLifecycleProperties properties = new FileLifecycleProperties();
        fileLifecycleService = new FileLifecycleService(
                settleFileMapper,
                filePublishBatchMapper,
                fileStatusLogMapper,
                fileReissueRecordMapper,
                fileOperationLockService,
                fileCacheService,
                localMessageService,
                taskMetadataClient,
                objectStorageService,
                properties,
                new TestTransactionManager());
    }

    @Test
    void publishShouldRejectWhenTaskUnfinished() {
        PublishFileRequest request = new PublishFileRequest();
        request.setSettleDate(LocalDate.of(2026, 6, 26));
        request.setFileType("TRADE");
        request.setVersion(1);
        when(fileOperationLockService.publish(eq(request.getSettleDate()), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(taskMetadataClient.countUnfinished(request.getSettleDate(), "TRADE", 1)).thenReturn(1);

        assertThrows(BizException.class, () -> fileLifecycleService.publish(request));

        verify(settleFileMapper, never()).listPublishCandidates(any(), any(), any());
        verify(filePublishBatchMapper).updateStatus(any(), eq("FAILED"), any(), any(), any(), any());
    }

    @Test
    void revokeShouldUpdateStatusAndWriteLog() {
        RevokeFileRequest request = new RevokeFileRequest();
        request.setOperator("admin");
        request.setReason("文件数据异常");
        SettleFile file = generatedFile();
        when(fileOperationLockService.revoke(eq(file.getFileNo()), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(settleFileMapper.selectByFileNo(file.getFileNo())).thenReturn(file);
        when(settleFileMapper.updateToRevoked(file.getFileNo(), request.getReason())).thenReturn(1);
        when(localMessageService.saveStatusEvent(any(), eq("FILE_REVOKED"))).thenReturn("MSG001");

        fileLifecycleService.revoke(file.getFileNo(), request);

        verify(settleFileMapper).updateToRevoked(file.getFileNo(), request.getReason());
        verify(fileStatusLogMapper).insert(any());
        verify(fileCacheService).evictFileMeta(file.getFileNo());
        verify(fileCacheService).evictFileList(file.getSettleDate(), file.getMemberId());
    }

    private SettleFile generatedFile() {
        SettleFile file = new SettleFile();
        file.setFileNo("FILE001");
        file.setSettleDate(LocalDate.of(2026, 6, 26));
        file.setMemberId("0001");
        file.setFileType("TRADE");
        file.setVersion(1);
        file.setStatus(FileStatusEnum.GENERATED.getCode());
        file.setFileName("trade_0001_20260626.csv");
        file.setFileMd5("md5");
        file.setFileSize(10L);
        file.setStorageBucket("exchange-clear");
        file.setStoragePath("20260626/0001/v1/TRADE/trade_0001_20260626.csv");
        return file;
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
