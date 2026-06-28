package com.wuxx.exchangeclear.task.service;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.IdGenerator;
import com.wuxx.exchangeclear.enums.FileTypeEnum;
import com.wuxx.exchangeclear.enums.TaskStatusEnum;
import com.wuxx.exchangeclear.member.entity.SettleMember;
import com.wuxx.exchangeclear.member.service.MemberService;
import com.wuxx.exchangeclear.mq.message.FileGenerateTaskMessage;
import com.wuxx.exchangeclear.mq.producer.FileGenerateTaskProducer;
import com.wuxx.exchangeclear.task.dto.BatchGenerateRequest;
import com.wuxx.exchangeclear.task.dto.BatchGenerateResponse;
import com.wuxx.exchangeclear.task.dto.BatchSendRequest;
import com.wuxx.exchangeclear.task.dto.BatchSendResponse;
import com.wuxx.exchangeclear.task.dto.CreateTaskRequest;
import com.wuxx.exchangeclear.task.dto.CreateTaskResponse;
import com.wuxx.exchangeclear.task.dto.CreateSingleTaskRequest;
import com.wuxx.exchangeclear.task.dto.CreateSingleTaskResponse;
import com.wuxx.exchangeclear.task.dto.TaskGenerateResponse;
import com.wuxx.exchangeclear.task.dto.TaskResendResponse;
import com.wuxx.exchangeclear.task.dto.TaskStatusUpdateRequest;
import com.wuxx.exchangeclear.task.dto.TaskVO;
import com.wuxx.exchangeclear.task.entity.SettleFileTask;
import com.wuxx.exchangeclear.task.lock.TaskResendLockService;
import com.wuxx.exchangeclear.task.mapper.SettleFileTaskMapper;
import com.wuxx.exchangeclear.worker.client.FileGenerateClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final MemberService memberService;

    private final SettleFileTaskMapper settleFileTaskMapper;

    private final FileGenerateTaskProducer fileGenerateTaskProducer;

    private final FileGenerateClient fileGenerateClient;

    private final TaskResendLockService taskResendLockService;

    public CreateTaskResponse createTasks(CreateTaskRequest request) {
        FileTypeEnum fileType = FileTypeEnum.require(request.getFileType());
        List<SettleMember> members = memberService.listActiveMembers();
        if (members.isEmpty()) {
            throw new BizException("没有可用会员，请先初始化模拟数据");
        }

        int createdCount = 0;
        int sentCount = 0;
        int sendFailedCount = 0;
        int existsCount = 0;
        for (SettleMember member : members) {
            SettleFileTask task = buildTask(request, fileType, member);
            int affected = settleFileTaskMapper.insertIgnore(task);
            if (affected > 0) {
                createdCount++;
                if (sendTaskMessage(task)) {
                    sentCount++;
                } else {
                    sendFailedCount++;
                }
            } else {
                existsCount++;
            }
        }
        return new CreateTaskResponse(
                request.getSettleDate(), fileType.getCode(), createdCount, sentCount, sendFailedCount, existsCount);
    }

    public CreateSingleTaskResponse createSingleTask(CreateSingleTaskRequest request) {
        FileTypeEnum fileType = FileTypeEnum.require(request.getFileType());
        SettleFileTask task = new SettleFileTask();
        task.setTaskNo(IdGenerator.next("TASK"));
        task.setSettleDate(request.getSettleDate());
        task.setMemberId(request.getMemberId());
        task.setFileType(fileType.getCode());
        task.setVersion(request.getVersion());
        task.setStatus(TaskStatusEnum.INIT.getCode());

        int affected = settleFileTaskMapper.insertIgnore(task);
        SettleFileTask saved = affected > 0
                ? task
                : findTaskByBiz(request.getSettleDate(), request.getMemberId(), fileType.getCode(), request.getVersion());
        if (saved == null) {
            throw new BizException("创建文件生成任务失败");
        }
        if (affected > 0 && Boolean.TRUE.equals(request.getAutoSend())) {
            sendTaskMessage(task);
            saved = getTask(task.getTaskNo());
        }
        return new CreateSingleTaskResponse(saved.getTaskNo(), saved.getStatus(), affected > 0);
    }

    public List<TaskVO> list(LocalDate settleDate, String status) {
        return settleFileTaskMapper.list(settleDate, status)
                .stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    public TaskGenerateResponse generate(String taskNo) {
        return fileGenerateClient.generate(taskNo);
    }

    public BatchGenerateResponse generateBatch(BatchGenerateRequest request) {
        FileTypeEnum fileType = FileTypeEnum.require(request.getFileType());
        List<SettleFileTask> tasks = settleFileTaskMapper.selectPendingTasks(request.getSettleDate(), fileType.getCode());
        int successCount = 0;
        int failedCount = 0;
        for (SettleFileTask task : tasks) {
            try {
                generate(task.getTaskNo());
                successCount++;
            } catch (Exception e) {
                failedCount++;
                log.warn("批量同步生成任务失败, taskNo={}", task.getTaskNo(), e);
            }
        }
        return new BatchGenerateResponse(successCount, failedCount);
    }

    public BatchSendResponse sendBatch(BatchSendRequest request) {
        FileTypeEnum fileType = FileTypeEnum.require(request.getFileType());
        String status = normalizeStatus(request.getStatus());
        validateSendStatus(status);
        List<SettleFileTask> tasks = settleFileTaskMapper.selectTasksForSend(
                request.getSettleDate(), fileType.getCode(), status);

        int sentCount = 0;
        int failedCount = 0;
        for (SettleFileTask task : tasks) {
            if (sendTaskMessage(task)) {
                sentCount++;
            } else {
                failedCount++;
            }
        }
        return new BatchSendResponse(tasks.size(), sentCount, failedCount);
    }

    public TaskResendResponse resend(String taskNo) {
        return taskResendLockService.execute(taskNo, () -> resendWithLock(taskNo));
    }

    private TaskResendResponse resendWithLock(String taskNo) {
        SettleFileTask task = getTask(taskNo);
        if (TaskStatusEnum.GENERATED.getCode().equals(task.getStatus())) {
            throw new BizException("任务已生成，不允许重投：" + taskNo);
        }
        if (TaskStatusEnum.GENERATING.getCode().equals(task.getStatus())) {
            throw new BizException("任务正在生成中，不允许重投：" + taskNo);
        }
        if (!allowSend(task.getStatus())) {
            throw new BizException("当前任务状态不允许重投：" + task.getStatus());
        }
        if (!sendTaskMessage(task)) {
            throw new BizException("任务消息重投失败：" + taskNo);
        }
        return new TaskResendResponse(taskNo, TaskStatusEnum.SENT.getCode());
    }

    public void updateStatus(String taskNo, TaskStatusUpdateRequest request) {
        getTask(taskNo);
        String fromStatus = normalizeStatus(request.getFromStatus());
        String toStatus = normalizeStatus(request.getToStatus());
        validateInternalStatusTransition(fromStatus, toStatus);
        int affected = settleFileTaskMapper.updateStatusByCurrent(
                taskNo, fromStatus, toStatus, truncateNullable(request.getErrorMessage()));
        if (affected == 0) {
            throw new BizException("任务状态已变化，请刷新后重试：" + taskNo);
        }
    }

    public int countUnfinished(LocalDate settleDate, String fileType, Integer version) {
        String normalizedFileType = StringUtils.hasText(fileType) ? FileTypeEnum.require(fileType).getCode() : null;
        return settleFileTaskMapper.countUnfinished(settleDate, normalizedFileType, version);
    }

    public int countGenerated(LocalDate settleDate, String fileType, Integer version) {
        String normalizedFileType = StringUtils.hasText(fileType) ? FileTypeEnum.require(fileType).getCode() : null;
        return settleFileTaskMapper.countGenerated(settleDate, normalizedFileType, version);
    }

    private SettleFileTask buildTask(CreateTaskRequest request, FileTypeEnum fileType, SettleMember member) {
        SettleFileTask task = new SettleFileTask();
        task.setTaskNo(IdGenerator.next("TASK"));
        task.setSettleDate(request.getSettleDate());
        task.setMemberId(member.getMemberId());
        task.setFileType(fileType.getCode());
        task.setVersion(request.getVersion());
        task.setStatus(TaskStatusEnum.INIT.getCode());
        return task;
    }

    private boolean sendTaskMessage(SettleFileTask task) {
        if (!allowSend(task.getStatus())) {
            log.info("[MQ-PRODUCER] task status not allow send, taskNo={}, status={}",
                    task.getTaskNo(), task.getStatus());
            return false;
        }

        FileGenerateTaskMessage message = buildMessage(task);
        String messageId;
        try {
            messageId = fileGenerateTaskProducer.send(message);
        } catch (Exception e) {
            String errorMessage = truncate(rootMessage(e));
            log.error("[MQ-PRODUCER] send file generate task failed, taskNo={}, error={}",
                    task.getTaskNo(), errorMessage, e);
            settleFileTaskMapper.updateSendFailed(task.getTaskNo(), errorMessage);
            return false;
        }

        settleFileTaskMapper.updateSent(task.getTaskNo(), messageId);
        return true;
    }

    private FileGenerateTaskMessage buildMessage(SettleFileTask task) {
        return FileGenerateTaskMessage.builder()
                .messageId(IdGenerator.next("MSG"))
                .taskNo(task.getTaskNo())
                .settleDate(task.getSettleDate())
                .memberId(task.getMemberId())
                .fileType(task.getFileType())
                .version(task.getVersion())
                .createdAt(System.currentTimeMillis())
                .build();
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return TaskStatusEnum.require(status.trim()).getCode();
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        }
    }

    private void validateSendStatus(String status) {
        if (status == null) {
            return;
        }
        if (!allowSend(status)) {
            throw new BizException("当前任务状态不允许投递：" + status);
        }
    }

    private boolean allowSend(String status) {
        return TaskStatusEnum.INIT.getCode().equals(status)
                || TaskStatusEnum.SENT.getCode().equals(status)
                || TaskStatusEnum.FAILED.getCode().equals(status)
                || TaskStatusEnum.SEND_FAILED.getCode().equals(status);
    }

    private void validateInternalStatusTransition(String fromStatus, String toStatus) {
        boolean allowToGenerating = TaskStatusEnum.GENERATING.getCode().equals(toStatus)
                && (TaskStatusEnum.INIT.getCode().equals(fromStatus)
                || TaskStatusEnum.SENT.getCode().equals(fromStatus)
                || TaskStatusEnum.FAILED.getCode().equals(fromStatus));
        boolean allowGeneratingFinished = TaskStatusEnum.GENERATING.getCode().equals(fromStatus)
                && (TaskStatusEnum.GENERATED.getCode().equals(toStatus)
                || TaskStatusEnum.FAILED.getCode().equals(toStatus));
        if (!allowToGenerating && !allowGeneratingFinished) {
            throw new BizException("不允许的任务状态流转：" + fromStatus + " -> " + toStatus);
        }
    }

    private SettleFileTask getTask(String taskNo) {
        SettleFileTask task = settleFileTaskMapper.selectByTaskNo(taskNo);
        if (task == null) {
            throw new BizException("任务不存在：" + taskNo);
        }
        return task;
    }

    private SettleFileTask findTaskByBiz(LocalDate settleDate, String memberId, String fileType, Integer version) {
        return settleFileTaskMapper.list(settleDate, null)
                .stream()
                .filter(task -> memberId.equals(task.getMemberId())
                        && fileType.equals(task.getFileType())
                        && version.equals(task.getVersion()))
                .findFirst()
                .orElse(null);
    }

    private TaskVO toVO(SettleFileTask task) {
        TaskVO vo = new TaskVO();
        vo.setTaskNo(task.getTaskNo());
        vo.setSettleDate(task.getSettleDate());
        vo.setMemberId(task.getMemberId());
        vo.setFileType(task.getFileType());
        vo.setVersion(task.getVersion());
        vo.setStatus(task.getStatus());
        vo.setRetryCount(task.getRetryCount());
        vo.setMaxRetryCount(task.getMaxRetryCount());
        vo.setLastMessageId(task.getLastMessageId());
        vo.setLastSendTime(task.getLastSendTime());
        vo.setLastConsumeTime(task.getLastConsumeTime());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setStartTime(task.getStartTime());
        vo.setEndTime(task.getEndTime());
        return vo;
    }

    private String truncate(String message) {
        if (message == null) {
            return "未知异常";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private String truncateNullable(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        return truncate(message);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
