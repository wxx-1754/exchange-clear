package com.wuxx.exchangeclear.file.client;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.task.dto.CreateSingleTaskRequest;
import com.wuxx.exchangeclear.task.dto.CreateSingleTaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class TaskMetadataClient {

    private final TaskServiceFeignClient taskServiceFeignClient;

    public int countUnfinished(LocalDate settleDate, String fileType, Integer version) {
        Result<Integer> result = taskServiceFeignClient.countUnfinished(settleDate, fileType, version);
        if (result == null || result.getCode() == null || result.getCode() != 0) {
            throw new BizException(result == null ? "查询任务完成情况失败" : result.getMessage());
        }
        return result.getData() == null ? 0 : result.getData();
    }

    public int countGenerated(LocalDate settleDate, String fileType, Integer version) {
        Result<Integer> result = taskServiceFeignClient.countGenerated(settleDate, fileType, version);
        if (result == null || result.getCode() == null || result.getCode() != 0) {
            throw new BizException(result == null ? "查询已生成任务数量失败" : result.getMessage());
        }
        return result.getData() == null ? 0 : result.getData();
    }

    public CreateSingleTaskResponse createSingleTask(CreateSingleTaskRequest request) {
        Result<CreateSingleTaskResponse> result = taskServiceFeignClient.createSingleTask(request);
        if (result == null || result.getCode() == null || result.getCode() != 0) {
            throw new BizException(result == null ? "创建新版本任务失败" : result.getMessage());
        }
        return result.getData();
    }
}
