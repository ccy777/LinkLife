package com.linklife.identity;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identity 扫描根契约：启动类位于领域根 package，@MapperScan 精确指向 identity.mapper；
 * controller/service/mapper 均位于 com.linklife.identity 之下（默认扫描可发现）。
 */
class IdentityScanRootContractTest {

    @Test
    void applicationClassLocatedAtDomainRoot() {
        assertThat(IdentityServiceApplication.class.getPackageName()).isEqualTo("com.linklife.identity");
        assertThat(IdentityServiceApplication.class.isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class))
                .isTrue();
    }

    @Test
    void mapperScanCoversIdentityMapperPackage() {
        MapperScan mapperScan = IdentityServiceApplication.class.getAnnotation(MapperScan.class);
        assertThat(mapperScan).isNotNull();
        assertThat(mapperScan.value()).containsExactly("com.linklife.identity.mapper");
    }

    @Test
    void controllerServiceMapperUnderDomainRoot() throws Exception {
        for (String name : new String[]{
                "com.linklife.identity.controller.UserController",
                "com.linklife.identity.service.IUserService",
                "com.linklife.identity.service.impl.UserServiceImpl",
                "com.linklife.identity.service.impl.UserInfoServiceImpl",
                "com.linklife.identity.mapper.UserMapper",
                "com.linklife.identity.mapper.UserInfoMapper"
        }) {
            Class<?> clazz = Class.forName(name);
            assertThat(clazz.getPackageName()).startsWith("com.linklife.identity.");
        }
    }
}
