package com.linklife.merchant;

import com.linklife.common.web.context.UserContextFilter;
import com.linklife.common.web.exception.GlobalExceptionHandler;
import com.linklife.common.web.security.AdminAuthorizationProperties;
import com.linklife.shared.cache.CacheClient;
import com.linklife.shared.config.CacheRebuildExecutorConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Merchant Service 启动类：位于领域根 package；common-web 共享配置（含 Admin 白名单）显式 @Import。
 */
@SpringBootApplication
@MapperScan("com.linklife.merchant.mapper")
@Import({GlobalExceptionHandler.class, UserContextFilter.class, AdminAuthorizationProperties.class,
        CacheClient.class, CacheRebuildExecutorConfig.class})
public class MerchantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerchantServiceApplication.class, args);
    }
}
