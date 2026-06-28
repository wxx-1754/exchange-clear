package com.wuxx.exchangeclear.file.service;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.IdGenerator;
import com.wuxx.exchangeclear.enums.FileStatusEnum;
import com.wuxx.exchangeclear.enums.FileTypeEnum;
import com.wuxx.exchangeclear.file.cache.FileCacheService;
import com.wuxx.exchangeclear.file.client.TaskMetadataClient;
import com.wuxx.exchangeclear.file.config.FileLifecycleProperties;
import com.wuxx.exchangeclear.file.dto.FileStatusLogVO;
import com.wuxx.exchangeclear.file.dto.PublishFileRequest;
import com.wuxx.exchangeclear.file.dto.PublishFileResponse;
import com.wuxx.exchangeclear.file.dto.ReissueFileRequest;
import com.wuxx.exchangeclear.file.dto.ReissueFileResponse;
import com.wuxx.exchangeclear.file.dto.RevokeFileRequest;
import com.wuxx.exchangeclear.file.dto.RevokeFileResponse;
import com.wuxx.exchangeclear.file.entity.FilePublishBatch;
import com.wuxx.exchangeclear.file.entity.FileReissueRecord;
import com.wuxx.exchangeclear.file.entity.FileStatusLog;
import com.wuxx.exchangeclear.file.entity.SettleFile;
import com.wuxx.exchangeclear.file.lock.FileOperationLockService;
import com.wuxx.exchangeclear.file.mapper.FilePublishBatchMapper;
import com.wuxx.exchangeclear.file.mapper.FileReissueRecordMapper;
import com.wuxx.exchangeclear.file.mapper.FileStatusLogMapper;
import com.wuxx.exchangeclear.file.mapper.SettleFileMapper;
import com.wuxx.exchangeclear.file.mq.FileStatusEventMessage;
import com.wuxx.exchangeclear.mq.message.FileGenerateTaskMessage;
import com.wuxx.exchangeclear.storage.ObjectStorageService;
import com.wuxx.exchangeclear.task.dto.CreateSingleTaskRequest;
import com.wuxx.exchangeclear.task.dto.CreateSingleTaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FileLifecycleService {

    private final SettleFileMapper settleFileMapper;

    private final FilePublishBatchMapper filePublishBatchMapper;

    private final FileStatusLogMapper fileStatusLogMapper;

    private final FileReissueRecordMapper fileReissueRecordMapper;

    private final FileOperationLockService fileOperationLockService;

    private final FileCacheService fileCacheService;

    private final LocalMessageService localMessageService;

    private final TaskMetadataClient taskMetadataClient;

    private final ObjectStorageService objectStorageService;

    private final FileLifecycleProperties fileLifecycleProperties;

    private final PlatformTransactionManager transactionManager;

    public PublishFileResponse publish(PublishFileRequest request) {
        normalizePublishRequest(request);
        return fileOperationLockService.publish(request.getSettleDate(), () -> {
            String batchNo = createPublishingBatch(request);
            try {
                return executeRequired(() -> publishInTransaction(batchNo, request));
            } catch (RuntimeException e) {
                markBatchFailed(batchNo, e.getMessage());
                throw e;
            }
        });
    }

    public RevokeFileResponse revoke(String fileNo, RevokeFileRequest request) {
        return fileOperationLockService.revoke(fileNo, () ->
                executeRequired(() -> revokeInTransaction(fileNo, request)));
    }

    public ReissueFileResponse reissue(String fileNo, ReissueFileRequest request) {
        return fileOperationLockService.reissue(fileNo, () ->
                executeRequired(() -> reissueInTransaction(fileNo, request)));
    }

    public List<FilePublishBatch> listPublishBatches(LocalDate settleDate) {
        return filePublishBatchMapper.list(settleDate);
    }

    public List<FileStatusLogVO> listStatusLogs(String fileNo) {
        return fileStatusLogMapper.listByFileNo(fileNo)
                .stream()
                .map(this::toStatusLogVO)
                .collect(Collectors.toList());
    }

    private String createPublishingBatch(PublishFileRequest request) {
        String batchNo = IdGenerator.next("PUB");
        FilePublishBatch batch = new FilePublishBatch();
        batch.setBatchNo(batchNo);
        batch.setSettleDate(request.getSettleDate());
        batch.setFileType(request.getFileType());
        batch.setVersion(request.getVersion());
        batch.setStatus("INIT");
        batch.setTotalCount(0);
        batch.setSuccessCount(0);
        batch.setFailedCount(0);
        batch.setOperator(request.getOperator());
        filePublishBatchMapper.insert(batch);
        filePublishBatchMapper.updateStatus(batchNo, "PUBLISHING", null, null, null, null);
        return batchNo;
    }

    private PublishFileResponse publishInTransaction(String batchNo, PublishFileRequest request) {
        int generatedTaskCount = validateTaskComplete(request);
        List<SettleFile> files = settleFileMapper.listPublishCandidates(
                request.getSettleDate(), request.getFileType(), request.getVersion());
        if (files.isEmpty()) {
            throw new BizException("待发布文件不存在，禁止发布");
        }
        validatePublishFiles(files, generatedTaskCount);

        List<String> messageIds = new ArrayList<>();
        for (SettleFile file : files) {
            int affected = settleFileMapper.updateToPublished(file.getFileNo(), "文件发布");
            if (affected == 0) {
                throw new BizException("文件状态已变化，请刷新后重试：" + file.getFileNo());
            }
            fileStatusLogMapper.insert(buildStatusLog(file, FileStatusEnum.PUBLISHED.getCode(),
                    "PUBLISH", batchNo, request.getOperator(), "文件发布"));
            messageIds.add(localMessageService.saveStatusEvent(buildStatusEvent(file,
                    FileStatusEnum.PUBLISHED.getCode(), request.getOperator()), "FILE_PUBLISHED"));
        }
        filePublishBatchMapper.updateStatus(batchNo, "SUCCESS", files.size(), files.size(), 0, null);
        runAfterCommit(messageIds, files);
        return new PublishFileResponse(batchNo, request.getSettleDate(), files.size(), "SUCCESS");
    }

    private RevokeFileResponse revokeInTransaction(String fileNo, RevokeFileRequest request) {
        SettleFile file = getFile(fileNo);
        if (!FileStatusEnum.GENERATED.getCode().equals(file.getStatus())
                && !FileStatusEnum.PUBLISHED.getCode().equals(file.getStatus())) {
            throw new BizException("当前文件状态不允许撤销");
        }

        int affected = settleFileMapper.updateToRevoked(fileNo, request.getReason());
        if (affected == 0) {
            throw new BizException("文件状态已变化，请刷新后重试：" + fileNo);
        }
        fileStatusLogMapper.insert(buildStatusLog(file, FileStatusEnum.REVOKED.getCode(),
                "REVOKE", null, request.getOperator(), request.getReason()));
        String messageId = localMessageService.saveStatusEvent(buildStatusEvent(file,
                FileStatusEnum.REVOKED.getCode(), request.getOperator()), "FILE_REVOKED");
        runAfterCommit(messageId, file);
        return new RevokeFileResponse(fileNo, FileStatusEnum.REVOKED.getCode());
    }

    private ReissueFileResponse reissueInTransaction(String fileNo, ReissueFileRequest request) {
        SettleFile file = getFile(fileNo);
        if (!FileStatusEnum.GENERATED.getCode().equals(file.getStatus())
                && !FileStatusEnum.PUBLISHED.getCode().equals(file.getStatus())
                && !FileStatusEnum.REVOKED.getCode().equals(file.getStatus())) {
            throw new BizException("当前文件状态不允许重发");
        }

        int newVersion = file.getVersion() + 1;
        int affected = settleFileMapper.updateToReissued(fileNo, request.getReason());
        if (affected == 0) {
            throw new BizException("文件状态已变化，请刷新后重试：" + fileNo);
        }
        fileStatusLogMapper.insert(buildStatusLog(file, FileStatusEnum.REISSUED.getCode(),
                "REISSUE", null, request.getOperator(), request.getReason()));

        CreateSingleTaskResponse taskResponse = createNewVersionTask(file, newVersion);
        String reissueNo = IdGenerator.next("REISSUE");
        FileReissueRecord record = new FileReissueRecord();
        record.setReissueNo(reissueNo);
        record.setOldFileNo(fileNo);
        record.setSettleDate(file.getSettleDate());
        record.setMemberId(file.getMemberId());
        record.setFileType(file.getFileType());
        record.setOldVersion(file.getVersion());
        record.setNewVersion(newVersion);
        record.setStatus(taskResponse.getStatus());
        record.setReason(request.getReason());
        record.setOperator(request.getOperator());
        fileReissueRecordMapper.insert(record);

        List<String> messageIds = new ArrayList<>();
        messageIds.add(localMessageService.saveStatusEvent(buildStatusEvent(file,
                FileStatusEnum.REISSUED.getCode(), request.getOperator()), "FILE_REISSUED"));
        messageIds.add(localMessageService.saveGenerateTask(buildGenerateTaskMessage(taskResponse, file, newVersion)));
        runAfterCommit(messageIds, file);
        return new ReissueFileResponse(reissueNo, fileNo, newVersion, "INIT");
    }

    private void normalizePublishRequest(PublishFileRequest request) {
        if (StringUtils.hasText(request.getFileType())) {
            request.setFileType(FileTypeEnum.require(request.getFileType()).getCode());
        }
    }

    private int validateTaskComplete(PublishFileRequest request) {
        if (!fileLifecycleProperties.getPublish().isCheckTaskComplete()) {
            return -1;
        }
        int unfinishedCount = taskMetadataClient.countUnfinished(
                request.getSettleDate(), request.getFileType(), request.getVersion());
        if (unfinishedCount > 0) {
            throw new BizException("仍有文件生成任务未完成，禁止发布");
        }
        return taskMetadataClient.countGenerated(request.getSettleDate(), request.getFileType(), request.getVersion());
    }

    private void validatePublishFiles(List<SettleFile> files, int generatedTaskCount) {
        if (generatedTaskCount >= 0 && files.size() != generatedTaskCount) {
            throw new BizException("待发布文件数量与已生成任务数量不一致，禁止发布");
        }
        for (SettleFile file : files) {
            if (fileLifecycleProperties.getPublish().isCheckFileMeta()) {
                if (!StringUtils.hasText(file.getFileName())
                        || !StringUtils.hasText(file.getFileMd5())
                        || file.getFileSize() == null || file.getFileSize() <= 0
                        || !StringUtils.hasText(file.getStorageBucket())
                        || !StringUtils.hasText(file.getStoragePath())) {
                    throw new BizException("文件元数据不完整，禁止发布：" + file.getFileNo());
                }
            }
            if (fileLifecycleProperties.getPublish().isCheckMinioExists()
                    && !objectStorageService.exists(file.getStoragePath())) {
                throw new BizException("对象存储文件不存在，禁止发布：" + file.getFileNo());
            }
        }
    }

    private CreateSingleTaskResponse createNewVersionTask(SettleFile file, int newVersion) {
        CreateSingleTaskRequest taskRequest = new CreateSingleTaskRequest();
        taskRequest.setSettleDate(file.getSettleDate());
        taskRequest.setMemberId(file.getMemberId());
        taskRequest.setFileType(file.getFileType());
        taskRequest.setVersion(newVersion);
        taskRequest.setAutoSend(false);
        return taskMetadataClient.createSingleTask(taskRequest);
    }

    private FileGenerateTaskMessage buildGenerateTaskMessage(CreateSingleTaskResponse taskResponse,
                                                             SettleFile file,
                                                             int newVersion) {
        return FileGenerateTaskMessage.builder()
                .messageId(IdGenerator.next("MSG"))
                .taskNo(taskResponse.getTaskNo())
                .settleDate(file.getSettleDate())
                .memberId(file.getMemberId())
                .fileType(file.getFileType())
                .version(newVersion)
                .createdAt(System.currentTimeMillis())
                .build();
    }

    private SettleFile getFile(String fileNo) {
        SettleFile file = settleFileMapper.selectByFileNo(fileNo);
        if (file == null) {
            throw new BizException("文件不存在：" + fileNo);
        }
        return file;
    }

    private FileStatusLog buildStatusLog(SettleFile file, String afterStatus, String operationType,
                                         String batchNo, String operator, String reason) {
        FileStatusLog log = new FileStatusLog();
        log.setFileNo(file.getFileNo());
        log.setSettleDate(file.getSettleDate());
        log.setMemberId(file.getMemberId());
        log.setFileType(file.getFileType());
        log.setVersion(file.getVersion());
        log.setBeforeStatus(file.getStatus());
        log.setAfterStatus(afterStatus);
        log.setOperationType(operationType);
        log.setBatchNo(batchNo);
        log.setOperator(operator);
        log.setReason(reason);
        return log;
    }

    private FileStatusEventMessage buildStatusEvent(SettleFile file, String eventType, String operator) {
        return FileStatusEventMessage.builder()
                .eventType(eventType)
                .fileNo(file.getFileNo())
                .settleDate(file.getSettleDate())
                .memberId(file.getMemberId())
                .fileType(file.getFileType())
                .version(file.getVersion())
                .operator(operator)
                .eventTime(System.currentTimeMillis())
                .build();
    }

    private void evict(SettleFile file) {
        fileCacheService.evictFileList(file.getSettleDate(), file.getMemberId());
        fileCacheService.evictFileMeta(file.getFileNo());
    }

    private void runAfterCommit(String messageId, SettleFile file) {
        List<String> messageIds = new ArrayList<>();
        messageIds.add(messageId);
        runAfterCommit(messageIds, file);
    }

    private void runAfterCommit(List<String> messageIds, SettleFile file) {
        List<SettleFile> files = new ArrayList<>();
        files.add(file);
        runAfterCommit(messageIds, files);
    }

    private void runAfterCommit(List<String> messageIds, List<SettleFile> files) {
        Runnable runnable = () -> {
            for (SettleFile file : files) {
                evict(file);
            }
            localMessageService.sendMessages(messageIds);
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runnable.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }

    private <T> T executeRequired(Supplier<T> supplier) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return transactionTemplate.execute(status -> supplier.get());
    }

    private void markBatchFailed(String batchNo, String errorMessage) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.executeWithoutResult(status ->
                filePublishBatchMapper.updateStatus(batchNo, "FAILED", null, null, null, errorMessage));
    }

    private FileStatusLogVO toStatusLogVO(FileStatusLog statusLog) {
        FileStatusLogVO vo = new FileStatusLogVO();
        vo.setFileNo(statusLog.getFileNo());
        vo.setSettleDate(statusLog.getSettleDate());
        vo.setMemberId(statusLog.getMemberId());
        vo.setFileType(statusLog.getFileType());
        vo.setVersion(statusLog.getVersion());
        vo.setBeforeStatus(statusLog.getBeforeStatus());
        vo.setAfterStatus(statusLog.getAfterStatus());
        vo.setOperationType(statusLog.getOperationType());
        vo.setBatchNo(statusLog.getBatchNo());
        vo.setOperator(statusLog.getOperator());
        vo.setReason(statusLog.getReason());
        vo.setCreatedAt(statusLog.getCreatedAt());
        return vo;
    }
}
