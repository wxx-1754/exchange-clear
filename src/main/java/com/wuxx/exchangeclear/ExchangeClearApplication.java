package com.wuxx.exchangeclear;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExchangeClearApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExchangeClearApplication.class, args);
    }

}
