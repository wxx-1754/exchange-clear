package com.wuxx.exchangeclear.audit;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@ConfigurationPropertiesScan(basePackages = "com.wuxx.exchangeclear")
@SpringBootApplication(scanBasePackages = {
        "com.wuxx.exchangeclear.common",
        "com.wuxx.exchangeclear.audit"
})
@MapperScan("com.wuxx.exchangeclear.audit.mapper")
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
