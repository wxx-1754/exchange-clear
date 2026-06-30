package com.wuxx.exchangeclear.download.audit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "exchange-clear.audit")
public class DownloadAuditProperties {

    private boolean enabled = true;

    private boolean saveTokenDigest = true;

    private int maxFailReasonLength = 1000;

    private int maxUserAgentLength = 512;
}
