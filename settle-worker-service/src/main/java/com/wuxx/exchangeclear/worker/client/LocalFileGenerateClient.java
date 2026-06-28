package com.wuxx.exchangeclear.worker.client;

import com.wuxx.exchangeclear.task.dto.TaskGenerateResponse;
import com.wuxx.exchangeclear.worker.FileGenerateWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "exchange-clear.task.file-generate-client", havingValue = "local", matchIfMissing = true)
public class LocalFileGenerateClient implements FileGenerateClient {

    private final FileGenerateWorker fileGenerateWorker;

    @Override
    public TaskGenerateResponse generate(String taskNo) {
        return fileGenerateWorker.generateSync(taskNo);
    }
}
