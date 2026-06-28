package com.wuxx.exchangeclear.mock;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@ConfigurationPropertiesScan(basePackages = "com.wuxx.exchangeclear")
@SpringBootApplication(scanBasePackages = {
        "com.wuxx.exchangeclear.common",
        "com.wuxx.exchangeclear.config",
        "com.wuxx.exchangeclear.mock"
})
@MapperScan({
        "com.wuxx.exchangeclear.member.mapper",
        "com.wuxx.exchangeclear.trade.mapper"
})
public class MockServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockServiceApplication.class, args);
    }
}
