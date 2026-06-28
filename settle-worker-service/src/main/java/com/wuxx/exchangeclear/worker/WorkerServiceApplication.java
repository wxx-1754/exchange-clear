package com.wuxx.exchangeclear.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.wuxx.exchangeclear.worker.fileclient")
@ConfigurationPropertiesScan(basePackages = "com.wuxx.exchangeclear")
@SpringBootApplication(scanBasePackages = {
        "com.wuxx.exchangeclear.common",
        "com.wuxx.exchangeclear.config",
        "com.wuxx.exchangeclear.generator",
        "com.wuxx.exchangeclear.mq.consumer",
        "com.wuxx.exchangeclear.storage",
        "com.wuxx.exchangeclear.worker"
})
@MapperScan({
        "com.wuxx.exchangeclear.task.mapper",
        "com.wuxx.exchangeclear.trade.mapper"
})
public class WorkerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerServiceApplication.class, args);
    }
}
