package com.wuxx.exchangeclear.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication
public class ExchangeGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExchangeGatewayApplication.class, args);
    }
}
