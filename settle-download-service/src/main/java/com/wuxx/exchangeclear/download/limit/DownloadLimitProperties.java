package com.wuxx.exchangeclear.download.limit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "exchange-clear.limit")
public class DownloadLimitProperties {

    private boolean failOpen = true;

    private Limit member = new Limit(true, 20);

    private Limit ip = new Limit(true, 50);

    @Data
    public static class Limit {

        private boolean enabled;

        private int permitsPerSecond;

        public Limit() {
        }

        public Limit(boolean enabled, int permitsPerSecond) {
            this.enabled = enabled;
            this.permitsPerSecond = permitsPerSecond;
        }
    }
}
