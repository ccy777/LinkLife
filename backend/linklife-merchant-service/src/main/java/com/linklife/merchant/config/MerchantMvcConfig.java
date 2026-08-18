package com.linklife.merchant.config;

import com.linklife.common.web.security.AdminAuthorizationProperties;
import com.linklife.common.web.security.AdminMutationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Merchant MVC 配置：注册 POST/PUT /shop 管理写授权拦截器 + 上传静态资源映射（/files/**）。
 */
@Configuration
public class MerchantMvcConfig implements WebMvcConfigurer {

    private final UploadProperties uploadProperties;
    private final AdminAuthorizationProperties adminAuthorizationProperties;

    public MerchantMvcConfig(UploadProperties uploadProperties,
                             AdminAuthorizationProperties adminAuthorizationProperties) {
        this.uploadProperties = uploadProperties;
        this.adminAuthorizationProperties = adminAuthorizationProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminMutationInterceptor(adminAuthorizationProperties))
                .addPathPatterns("/shop", "/shop/**")
                .order(3);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String prefix = uploadProperties.normalizedResourcePrefix();
        String location = uploadProperties.normalizedRootPath().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler(prefix + "**").addResourceLocations(location);
    }
}
