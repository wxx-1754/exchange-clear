package com.wuxx.exchangeclear.worker.client;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.task.dto.TaskGenerateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "exchange-clear.task.file-generate-client", havingValue = "feign", matchIfMissing = true)
public class FeignFileGenerateClient implements FileGenerateClient {

    private final WorkerFeignClient workerFeignClient;

    @Override
    public TaskGenerateResponse generate(String taskNo) {
        Result<TaskGenerateResponse> result = workerFeignClient.generate(taskNo);
        if (result == null) {
            throw new BizException("调用worker-service生成文件失败：" + taskNo);
        }
        if (result.getCode() == null || result.getCode() != 0) {
            throw new BizException(result.getMessage());
        }
        return result.getData();
    }
}
