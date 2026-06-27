package com.wuxx.exchangeclear.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "exchange-clear.mock")
public class MockProperties {

    private Integer batchSize = 1000;
}
