package com.wuxx.exchangeclear.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "exchange-clear.file")
public class ExchangeClearFileProperties {

    private String localRootPath = "/tmp/exchange-clear";

    private Integer pageSize = 1000;
}
