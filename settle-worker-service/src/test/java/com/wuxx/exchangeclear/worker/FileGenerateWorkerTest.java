package com.wuxx.exchangeclear.worker;

import com.wuxx.exchangeclear.enums.TaskStatusEnum;
import com.wuxx.exchangeclear.file.client.FileMetadataClient;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.file.dto.SaveGeneratedFileRequest;
import com.wuxx.exchangeclear.generator.FileGenerateContext;
import com.wuxx.exchangeclear.generator.FileGenerateResult;
import com.wuxx.exchangeclear.generator.FileGenerator;
import com.wuxx.exchangeclear.mq.message.FileGenerateTaskMessage;
import com.wuxx.exchangeclear.storage.ObjectStorageService;
import com.wuxx.exchangeclear.storage.StorageUploadResult;
import com.wuxx.exchangeclear.task.entity.SettleFileTask;
import com.wuxx.exchangeclear.task.mapper.SettleFileTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileGenerateWorkerTest {

    @Mock
    private SettleFileTaskMapper settleFileTaskMapper;

    @Mock
    private FileGenerator fileGenerator;

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private FileMetadataClient fileMetadataClient;

    private FileGenerateWorker worker;

    @BeforeEach
    void setUp() {
        worker = new FileGenerateWorker(
                settleFileTaskMapper,
                Collections.singletonList(fileGenerator),
                objectStorageService,
                fileMetadataClient
        );
    }

    @Test
    void handleShouldSkipAlreadyGeneratedTask() {
        SettleFileTask task = task(TaskStatusEnum.GENERATED.getCode());
        when(settleFileTaskMapper.selectByTaskNo(task.getTaskNo())).thenReturn(task);
        when(fileMetadataClient.getMetadataByBiz(task.getSettleDate(), task.getMemberId(), task.getFileType(), task.getVersion()))
                .thenReturn(metadata(task));

        worker.handle(message(task.getTaskNo()));

        verify(settleFileTaskMapper, never()).updateStatusToGenerating(anyString());
        verifyNoInteractions(fileGenerator, objectStorageService);
    }

    @Test
    void handleShouldGenerateAfterAcquiringSentTask() {
        SettleFileTask task = task(TaskStatusEnum.SENT.getCode());
        FileGenerateResult generateResult = new FileGenerateResult(
                "trade_0001_20260626.csv",
                Path.of("build", "trade_0001_20260626.csv"),
                10L,
                "md5"
        );
        StorageUploadResult uploadResult = new StorageUploadResult(
                "exchange-clear",
                "20260626/0001/v1/TRADE/trade_0001_20260626.csv",
                10L
        );

        when(settleFileTaskMapper.selectByTaskNo(task.getTaskNo())).thenReturn(task);
        when(settleFileTaskMapper.updateStatusToGenerating(task.getTaskNo())).thenReturn(1);
        when(fileGenerator.support(task.getFileType())).thenReturn(true);
        when(fileGenerator.generate(any(FileGenerateContext.class))).thenReturn(generateResult);
        when(objectStorageService.upload(generateResult.getLocalFilePath(), uploadResult.getObjectName()))
                .thenReturn(uploadResult);
        when(fileMetadataClient.saveGeneratedFile(any(SaveGeneratedFileRequest.class))).thenReturn(metadata(task));

        worker.handle(message(task.getTaskNo()));

        verify(settleFileTaskMapper).updateStatusToGenerating(task.getTaskNo());
        verify(objectStorageService).upload(generateResult.getLocalFilePath(), uploadResult.getObjectName());
        verify(fileMetadataClient).saveGeneratedFile(any(SaveGeneratedFileRequest.class));
        verify(settleFileTaskMapper).updateGenerated(task.getTaskNo());
    }

    private FileGenerateTaskMessage message(String taskNo) {
        FileGenerateTaskMessage message = new FileGenerateTaskMessage();
        message.setTaskNo(taskNo);
        message.setMessageId("MSG001");
        return message;
    }

    private SettleFileTask task(String status) {
        SettleFileTask task = new SettleFileTask();
        task.setTaskNo("TASK001");
        task.setSettleDate(LocalDate.of(2026, 6, 26));
        task.setMemberId("0001");
        task.setFileType("TRADE");
        task.setVersion(1);
        task.setStatus(status);
        return task;
    }

    private FileMetadataDTO metadata(SettleFileTask task) {
        FileMetadataDTO metadata = new FileMetadataDTO();
        metadata.setTaskNo(task.getTaskNo());
        metadata.setFileNo("FILE001");
        metadata.setSettleDate(task.getSettleDate());
        metadata.setMemberId(task.getMemberId());
        metadata.setFileType(task.getFileType());
        metadata.setFileName("trade_0001_20260626.csv");
        metadata.setFileSize(10L);
        metadata.setFileMd5("md5");
        metadata.setStorageBucket("exchange-clear");
        metadata.setStoragePath("20260626/0001/v1/TRADE/trade_0001_20260626.csv");
        metadata.setVersion(task.getVersion());
        return metadata;
    }
}
