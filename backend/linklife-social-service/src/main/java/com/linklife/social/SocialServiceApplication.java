package com.linklife.social;

import com.linklife.common.web.context.UserContextFilter;
import com.linklife.common.web.exception.GlobalExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * Social Service 启动类：领域根扫描；@EnableFeignClients 只扫 social.client；
 * common-web 共享配置显式 @Import。
 */
@SpringBootApplication
@MapperScan("com.linklife.social.mapper")
@EnableFeignClients(basePackages = "com.linklife.social.client")
@Import({GlobalExceptionHandler.class, UserContextFilter.class})
public class SocialServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SocialServiceApplication.class, args);
    }
}
