package com.wuxx.exchangeclear.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.wuxx.exchangeclear")
@ConfigurationPropertiesScan(basePackages = "com.wuxx.exchangeclear")
@SpringBootApplication(scanBasePackages = "com.wuxx.exchangeclear")
@MapperScan({
        "com.wuxx.exchangeclear.task.mapper",
        "com.wuxx.exchangeclear.member.mapper"
})
public class TaskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskServiceApplication.class, args);
    }
}
