package com.linklife.social;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Social 扫描根契约：启动类位于领域根；@MapperScan=social.mapper；Feign 只扫 social.client。
 */
class SocialScanRootContractTest {

    @Test
    void applicationLocatedAtDomainRootWithMapperAndFeignScan() {
        assertThat(SocialServiceApplication.class.getPackageName()).isEqualTo("com.linklife.social");
        SpringBootApplication app = SocialServiceApplication.class.getAnnotation(SpringBootApplication.class);
        assertThat(app).isNotNull();
        MapperScan mapperScan = SocialServiceApplication.class.getAnnotation(MapperScan.class);
        assertThat(mapperScan.value()).containsExactly("com.linklife.social.mapper");
        EnableFeignClients feign = SocialServiceApplication.class.getAnnotation(EnableFeignClients.class);
        assertThat(feign).isNotNull();
        assertThat(feign.basePackages()).containsExactly("com.linklife.social.client");
    }

    @Test
    void socialCoreTypesPresent() throws Exception {
        for (String name : new String[]{
                "com.linklife.social.controller.BlogController",
                "com.linklife.social.controller.FollowController",
                "com.linklife.social.service.impl.BlogServiceImpl",
                "com.linklife.social.service.impl.FollowServiceImpl",
                "com.linklife.social.mapper.BlogMapper",
                "com.linklife.social.mapper.FollowMapper"
        }) {
            assertThat(Class.forName(name)).isNotNull();
        }
    }
}
