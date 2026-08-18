package com.linklife.merchant;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Merchant 扫描根契约：启动类位于领域根 package，@MapperScan 精确指向 merchant.mapper；
 * controller/service/mapper 均位于 com.linklife.merchant 之下。
 */
class MerchantScanRootContractTest {

    @Test
    void applicationClassLocatedAtDomainRoot() {
        assertThat(MerchantServiceApplication.class.getPackageName()).isEqualTo("com.linklife.merchant");
        assertThat(MerchantServiceApplication.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.SpringBootApplication.class)).isTrue();
    }

    @Test
    void mapperScanCoversMerchantMapperPackage() {
        MapperScan mapperScan = MerchantServiceApplication.class.getAnnotation(MapperScan.class);
        assertThat(mapperScan).isNotNull();
        assertThat(mapperScan.value()).containsExactly("com.linklife.merchant.mapper");
    }

    @Test
    void controllerServiceMapperUnderDomainRoot() throws Exception {
        for (String name : new String[]{
                "com.linklife.merchant.controller.ShopController",
                "com.linklife.merchant.controller.ShopTypeController",
                "com.linklife.merchant.controller.UploadController",
                "com.linklife.merchant.service.IShopService",
                "com.linklife.merchant.service.impl.ShopServiceImpl",
                "com.linklife.merchant.service.impl.ShopTypeServiceImpl",
                "com.linklife.merchant.mapper.ShopMapper",
                "com.linklife.merchant.mapper.ShopTypeMapper"
        }) {
            Class<?> clazz = Class.forName(name);
            assertThat(clazz.getPackageName()).startsWith("com.linklife.merchant.");
        }
    }
}
