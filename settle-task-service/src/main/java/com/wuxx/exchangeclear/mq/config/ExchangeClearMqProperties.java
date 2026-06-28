package com.wuxx.exchangeclear.mq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "exchange-clear.mq")
public class ExchangeClearMqProperties {

    private Topic topic = new Topic();

    private Tag tag = new Tag();

    public String buildFileGenerateDestination(String fileType) {
        return topic.getFileGenerateTask() + ":" + resolveTag(fileType);
    }

    private String resolveTag(String fileType) {
        if (tag.getTrade().equalsIgnoreCase(fileType)) {
            return tag.getTrade();
        }
        return fileType;
    }

    @Data
    public static class Topic {

        private String fileGenerateTask = "file-generate-task";
    }

    @Data
    public static class Tag {

        private String trade = "TRADE";
    }
}
