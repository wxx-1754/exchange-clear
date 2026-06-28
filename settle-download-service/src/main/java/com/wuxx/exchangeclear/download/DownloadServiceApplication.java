package com.wuxx.exchangeclear.download;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.wuxx.exchangeclear.download.fileclient")
@ConfigurationPropertiesScan(basePackages = "com.wuxx.exchangeclear")
@SpringBootApplication(scanBasePackages = {
        "com.wuxx.exchangeclear.common",
        "com.wuxx.exchangeclear.config",
        "com.wuxx.exchangeclear.download",
        "com.wuxx.exchangeclear.storage"
})
public class DownloadServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DownloadServiceApplication.class, args);
    }
}
