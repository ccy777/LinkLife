package com.linklife.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stage 4 Gateway：WebFlux/Netty，Nacos Discovery consumer（register-enabled=false）。
 * 018B 只建立路由骨架，不实现正式会话认证 Filter。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
