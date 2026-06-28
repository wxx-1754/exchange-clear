package com.wuxx.exchangeclear.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "exchange-clear")
public class FileLifecycleProperties {

    private Publish publish = new Publish();

    private LocalMessage localMessage = new LocalMessage();

    @Data
    public static class Publish {

        private boolean checkTaskComplete = true;

        private boolean checkFileMeta = true;

        private boolean checkMinioExists = true;
    }

    @Data
    public static class LocalMessage {

        private String statusEventTopic = "file.status.event";

        private String fileGenerateTaskTopic = "file.generate.task";

        private int maxRetryCount = 5;

        private int retryIntervalSeconds = 60;

        private int batchSize = 100;
    }
}
