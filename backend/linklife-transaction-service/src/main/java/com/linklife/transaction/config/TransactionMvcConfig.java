package com.linklife.transaction.config;

import com.linklife.common.web.security.AdminAuthorizationProperties;
import com.linklife.common.web.security.AdminMutationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Transaction MVC 配置：只注册 POST /voucher 与 POST /voucher/seckill 管理写授权拦截器。
 */
@Configuration
public class TransactionMvcConfig implements WebMvcConfigurer {

    private final AdminAuthorizationProperties adminAuthorizationProperties;

    public TransactionMvcConfig(AdminAuthorizationProperties adminAuthorizationProperties) {
        this.adminAuthorizationProperties = adminAuthorizationProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminMutationInterceptor(adminAuthorizationProperties))
                .addPathPatterns("/voucher", "/voucher/seckill")
                .order(3);
    }
}
