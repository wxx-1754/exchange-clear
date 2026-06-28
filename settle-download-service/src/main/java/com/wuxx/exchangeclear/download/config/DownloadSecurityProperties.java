package com.wuxx.exchangeclear.download.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "exchange-clear.download")
public class DownloadSecurityProperties {

    private long tokenExpireSeconds = 300;

    private boolean oneTimeToken = false;

    private boolean bindIp = false;

    private List<String> allowedStatuses = Collections.singletonList("PUBLISHED");
}
