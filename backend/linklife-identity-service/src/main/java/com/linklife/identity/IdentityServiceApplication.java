package com.linklife.identity;

import com.linklife.common.web.context.UserContextFilter;
import com.linklife.common.web.exception.GlobalExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Identity Service 启动类：位于领域根 package，确保 controller/service/mapper/security 被 Spring 扫描；
 * common-web 共享配置通过显式 @Import 装配，不依赖宽泛扫描。
 */
@SpringBootApplication
@MapperScan("com.linklife.identity.mapper")
@Import({GlobalExceptionHandler.class, UserContextFilter.class})
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
