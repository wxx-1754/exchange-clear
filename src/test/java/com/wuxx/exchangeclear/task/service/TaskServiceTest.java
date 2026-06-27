package com.wuxx.exchangeclear.task.service;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.task.dto.BatchSendRequest;
import com.wuxx.exchangeclear.member.entity.SettleMember;
import com.wuxx.exchangeclear.member.service.MemberService;
import com.wuxx.exchangeclear.mq.message.FileGenerateTaskMessage;
import com.wuxx.exchangeclear.mq.producer.FileGenerateTaskProducer;
import com.wuxx.exchangeclear.task.dto.CreateTaskRequest;
import com.wuxx.exchangeclear.task.dto.CreateTaskResponse;
import com.wuxx.exchangeclear.task.mapper.SettleFileTaskMapper;
import com.wuxx.exchangeclear.worker.FileGenerateWorker;
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
    private FileGenerateWorker fileGenerateWorker;

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
}
