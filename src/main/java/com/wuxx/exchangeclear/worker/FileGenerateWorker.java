package com.wuxx.exchangeclear.worker;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.enums.TaskStatusEnum;
import com.wuxx.exchangeclear.file.entity.SettleFile;
import com.wuxx.exchangeclear.file.mapper.SettleFileMapper;
import com.wuxx.exchangeclear.file.service.SettlementFileService;
import com.wuxx.exchangeclear.generator.FileGenerateContext;
import com.wuxx.exchangeclear.generator.FileGenerateResult;
import com.wuxx.exchangeclear.generator.FileGenerator;
import com.wuxx.exchangeclear.mq.message.FileGenerateTaskMessage;
import com.wuxx.exchangeclear.storage.ObjectStorageService;
import com.wuxx.exchangeclear.storage.StorageUploadResult;
import com.wuxx.exchangeclear.task.entity.SettleFileTask;
import com.wuxx.exchangeclear.task.mapper.SettleFileTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileGenerateWorker {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SettleFileTaskMapper settleFileTaskMapper;

    private final SettleFileMapper settleFileMapper;

    private final List<FileGenerator> fileGenerators;

    private final ObjectStorageService objectStorageService;

    private final SettlementFileService settlementFileService;

    public void handle(FileGenerateTaskMessage message) {
        if (message == null || message.getTaskNo() == null) {
            log.warn("[WORKER] file generate message is invalid, message={}", message);
            return;
        }

        SettleFileTask task = settleFileTaskMapper.selectByTaskNo(message.getTaskNo());
        if (task == null) {
            log.warn("[WORKER] task not found, taskNo={}", message.getTaskNo());
            return;
        }
        generate(task, false);
    }

    public SettleFile generateSync(String taskNo) {
        SettleFileTask task = getTask(taskNo);
        SettleFile file = generate(task, true);
        if (file == null) {
            throw new BizException("当前任务状态不允许同步生成：" + task.getStatus());
        }
        return file;
    }

    private SettleFile generate(SettleFileTask task, boolean failWhenSkipped) {
        if (TaskStatusEnum.GENERATED.getCode().equals(task.getStatus())) {
            log.info("[WORKER] task already generated, skip, taskNo={}", task.getTaskNo());
            return requireGeneratedFile(task);
        }
        if (!allowGenerate(task)) {
            log.info("[WORKER] task status not allow generate, taskNo={}, status={}",
                    task.getTaskNo(), task.getStatus());
            if (failWhenSkipped) {
                throw new BizException("当前任务状态不允许生成：" + task.getStatus());
            }
            return null;
        }

        int updated = settleFileTaskMapper.updateStatusToGenerating(task.getTaskNo());
        if (updated == 0) {
            log.info("[WORKER] task already locked by another worker, taskNo={}", task.getTaskNo());
            if (failWhenSkipped) {
                throw new BizException("任务状态已变化，请刷新后重试：" + task.getTaskNo());
            }
            return null;
        }

        try {
            log.info("[WORKER] start generate file, taskNo={}, memberId={}", task.getTaskNo(), task.getMemberId());
            FileGenerator generator = findGenerator(task.getFileType());
            FileGenerateContext context = buildContext(task);
            FileGenerateResult generateResult = generator.generate(context);
            String objectName = buildObjectName(task, generateResult.getFileName());
            StorageUploadResult storageResult = objectStorageService.upload(generateResult.getLocalFilePath(), objectName);
            log.info("[WORKER] minio upload success, taskNo={}, objectName={}", task.getTaskNo(), objectName);

            SettleFile file = settlementFileService.saveGeneratedFile(task, generateResult, storageResult);
            log.info("[WORKER] task generated success, taskNo={}, fileNo={}", task.getTaskNo(), file.getFileNo());
            return file;
        } catch (RuntimeException e) {
            log.error("[WORKER] task generate failed, taskNo={}", task.getTaskNo(), e);
            settleFileTaskMapper.updateFailed(task.getTaskNo(), truncate(rootMessage(e)));
            throw e;
        } catch (Exception e) {
            log.error("[WORKER] task generate failed, taskNo={}", task.getTaskNo(), e);
            settleFileTaskMapper.updateFailed(task.getTaskNo(), truncate(rootMessage(e)));
            throw new IllegalStateException("文件生成任务执行失败", e);
        }
    }

    private SettleFileTask getTask(String taskNo) {
        SettleFileTask task = settleFileTaskMapper.selectByTaskNo(taskNo);
        if (task == null) {
            throw new BizException("任务不存在：" + taskNo);
        }
        return task;
    }

    private boolean allowGenerate(SettleFileTask task) {
        return TaskStatusEnum.INIT.getCode().equals(task.getStatus())
                || TaskStatusEnum.SENT.getCode().equals(task.getStatus())
                || TaskStatusEnum.FAILED.getCode().equals(task.getStatus());
    }

    private FileGenerator findGenerator(String fileType) {
        return fileGenerators.stream()
                .filter(generator -> generator.support(fileType))
                .findFirst()
                .orElseThrow(() -> new BizException("未找到文件生成器：" + fileType));
    }

    private FileGenerateContext buildContext(SettleFileTask task) {
        return FileGenerateContext.builder()
                .taskNo(task.getTaskNo())
                .settleDate(task.getSettleDate())
                .memberId(task.getMemberId())
                .fileType(task.getFileType())
                .version(task.getVersion())
                .build();
    }

    private String buildObjectName(SettleFileTask task, String fileName) {
        return task.getSettleDate().format(DATE_FORMATTER)
                + "/" + task.getMemberId()
                + "/v" + task.getVersion()
                + "/" + task.getFileType()
                + "/" + fileName;
    }

    private SettleFile requireGeneratedFile(SettleFileTask task) {
        SettleFile file = settleFileMapper.selectByBiz(
                task.getSettleDate(), task.getMemberId(), task.getFileType(), task.getVersion());
        if (file == null) {
            throw new BizException("任务已生成但文件元数据不存在：" + task.getTaskNo());
        }
        return file;
    }

    private String truncate(String message) {
        if (message == null) {
            return "未知异常";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
