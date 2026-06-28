package com.wuxx.exchangeclear.task.service;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.enums.TaskStatusEnum;
import com.wuxx.exchangeclear.task.dto.BatchSendRequest;
import com.wuxx.exchangeclear.member.entity.SettleMember;
import com.wuxx.exchangeclear.member.service.MemberService;
import com.wuxx.exchangeclear.mq.message.FileGenerateTaskMessage;
import com.wuxx.exchangeclear.mq.producer.FileGenerateTaskProducer;
import com.wuxx.exchangeclear.task.dto.CreateTaskRequest;
import com.wuxx.exchangeclear.task.dto.CreateTaskResponse;
import com.wuxx.exchangeclear.task.dto.TaskStatusUpdateRequest;
import com.wuxx.exchangeclear.task.entity.SettleFileTask;
import com.wuxx.exchangeclear.task.lock.TaskResendLockService;
import com.wuxx.exchangeclear.task.mapper.SettleFileTaskMapper;
import com.wuxx.exchangeclear.worker.client.FileGenerateClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private MemberService memberService;

    @Mock
    private SettleFileTaskMapper settleFileTaskMapper;

    @Mock
    private FileGenerateTaskProducer fileGenerateTaskProducer;

    @Mock
    private FileGenerateClient fileGenerateClient;

    @Mock
    private TaskResendLockService taskResendLockService;

    @InjectMocks
    private TaskService taskService;

    @Test
    void createTasksShouldSendMqForCreatedTasksOnly() {
        CreateTaskRequest request = request();
        when(memberService.listActiveMembers()).thenReturn(Arrays.asList(member("0001"), member("0002")));
        when(settleFileTaskMapper.insertIgnore(any())).thenReturn(1, 0);
        when(fileGenerateTaskProducer.send(any(FileGenerateTaskMessage.class))).thenReturn("MQ001");

        CreateTaskResponse response = taskService.createTasks(request);

        assertEquals(1, response.getCreatedCount());
        assertEquals(1, response.getSentCount());
        assertEquals(0, response.getSendFailedCount());
        assertEquals(1, response.getExistsCount());
        verify(settleFileTaskMapper).updateSent(anyString(), eq("MQ001"));
    }

    @Test
    void createTasksShouldMarkSendFailedWhenProducerThrowsException() {
        CreateTaskRequest request = request();
        when(memberService.listActiveMembers()).thenReturn(Arrays.asList(member("0001")));
        when(settleFileTaskMapper.insertIgnore(any())).thenReturn(1);
        when(fileGenerateTaskProducer.send(any(FileGenerateTaskMessage.class))).thenThrow(new IllegalStateException("mq down"));

        CreateTaskResponse response = taskService.createTasks(request);

        assertEquals(1, response.getCreatedCount());
        assertEquals(0, response.getSentCount());
        assertEquals(1, response.getSendFailedCount());
        assertEquals(0, response.getExistsCount());
        verify(settleFileTaskMapper).updateSendFailed(anyString(), eq("mq down"));
    }

    @Test
    void sendBatchShouldRejectGeneratedStatus() {
        BatchSendRequest request = new BatchSendRequest();
        request.setSettleDate(LocalDate.of(2026, 6, 26));
        request.setFileType("TRADE");
        request.setStatus("GENERATED");

        assertThrows(BizException.class, () -> taskService.sendBatch(request));
    }

    @Test
    void updateStatusShouldUpdateWhenTransitionAllowed() {
        TaskStatusUpdateRequest request = statusRequest(
                TaskStatusEnum.SENT.getCode(), TaskStatusEnum.GENERATING.getCode(), null);
        when(settleFileTaskMapper.selectByTaskNo("TASK001")).thenReturn(task(TaskStatusEnum.SENT.getCode()));
        when(settleFileTaskMapper.updateStatusByCurrent(
                "TASK001", TaskStatusEnum.SENT.getCode(), TaskStatusEnum.GENERATING.getCode(), null))
                .thenReturn(1);

        taskService.updateStatus("TASK001", request);

        verify(settleFileTaskMapper).updateStatusByCurrent(
                "TASK001", TaskStatusEnum.SENT.getCode(), TaskStatusEnum.GENERATING.getCode(), null);
    }

    @Test
    void updateStatusShouldFailWhenCurrentStatusChanged() {
        TaskStatusUpdateRequest request = statusRequest(
                TaskStatusEnum.GENERATING.getCode(), TaskStatusEnum.GENERATED.getCode(), null);
        when(settleFileTaskMapper.selectByTaskNo("TASK001")).thenReturn(task(TaskStatusEnum.GENERATING.getCode()));
        when(settleFileTaskMapper.updateStatusByCurrent(
                "TASK001", TaskStatusEnum.GENERATING.getCode(), TaskStatusEnum.GENERATED.getCode(), null))
                .thenReturn(0);

        assertThrows(BizException.class, () -> taskService.updateStatus("TASK001", request));
    }

    @Test
    void updateStatusShouldRejectInvalidTransition() {
        TaskStatusUpdateRequest request = statusRequest(
                TaskStatusEnum.GENERATED.getCode(), TaskStatusEnum.GENERATING.getCode(), null);
        when(settleFileTaskMapper.selectByTaskNo("TASK001")).thenReturn(task(TaskStatusEnum.GENERATED.getCode()));

        assertThrows(BizException.class, () -> taskService.updateStatus("TASK001", request));
    }

    private CreateTaskRequest request() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setSettleDate(LocalDate.of(2026, 6, 26));
        request.setFileType("TRADE");
        request.setVersion(1);
        return request;
    }

    private SettleMember member(String memberId) {
        SettleMember member = new SettleMember();
        member.setMemberId(memberId);
        return member;
    }

    private TaskStatusUpdateRequest statusRequest(String fromStatus, String toStatus, String errorMessage) {
        TaskStatusUpdateRequest request = new TaskStatusUpdateRequest();
        request.setFromStatus(fromStatus);
        request.setToStatus(toStatus);
        request.setErrorMessage(errorMessage);
        return request;
    }

    private SettleFileTask task(String status) {
        SettleFileTask task = new SettleFileTask();
        task.setTaskNo("TASK001");
        task.setStatus(status);
        return task;
    }
}
