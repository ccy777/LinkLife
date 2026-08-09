package com.linklife.merchant.config;

import com.linklife.common.web.security.AdminAuthorizationProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Merchant MVC 配置契约：Admin 拦截器只注册到 /shop 与 /shop/**；资源映射保持 /files/**。
 */
class MerchantMvcConfigContractTest {

    @Test
    void adminInterceptorRegisteredForShopPaths() throws Exception {
        String source = Files.readString(Paths.get(
                "src/main/java/com/linklife/merchant/config/MerchantMvcConfig.java"));
        assertThat(source).contains("new AdminMutationInterceptor(adminAuthorizationProperties)");
        assertThat(source).contains(".addPathPatterns(\"/shop\", \"/shop/**\")");
        assertThat(source).doesNotContain("addPathPatterns(\"/voucher");
    }

    @Test
    void resourceHandlerKeepsFilesPrefix() throws Exception {
        String source = Files.readString(Paths.get(
                "src/main/java/com/linklife/merchant/config/MerchantMvcConfig.java"));
        assertThat(source).contains("normalizedResourcePrefix()");
        assertThat(source).contains("addResourceHandler(prefix + \"**\")");
    }

    @Test
    void adminPropertiesStillUsesLinklifeSecurityPrefix() throws Exception {
        org.springframework.boot.context.properties.ConfigurationProperties props =
                AdminAuthorizationProperties.class.getAnnotation(
                        org.springframework.boot.context.properties.ConfigurationProperties.class);
        assertThat(props).isNotNull();
        assertThat(props.prefix()).isEqualTo("linklife.security");
    }

    @Test
    void adminInterceptionIsFailClosed() throws Exception {
        AdminAuthorizationProperties properties = new AdminAuthorizationProperties();
        properties.setAdminUserIds(java.util.Set.of());
        com.linklife.common.web.security.AdminMutationInterceptor interceptor =
                new com.linklife.common.web.security.AdminMutationInterceptor(properties);
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/shop");
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        com.linklife.common.core.context.UserContext.set(1L);
        try {
            assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        } finally {
            com.linklife.common.core.context.UserContext.clear();
        }
    }
}
