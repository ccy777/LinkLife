package com.linklife.social.client;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Social → Identity Feign 契约：name=linklife-identity-service、contextId=identityUserClient、path=/internal/users。
 */
class IdentityUserClientContractTest {

    @Test
    void feignNameMatchesRegisteredServiceId() {
        FeignClient feignClient = IdentityUserClient.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo("linklife-identity-service");
        assertThat(feignClient.contextId()).isEqualTo("identityUserClient");
        assertThat(feignClient.path()).isEqualTo("/internal/users");
        assertThat(feignClient.fallback()).isEqualTo(void.class);
        assertThat(feignClient.fallbackFactory()).isEqualTo(void.class);
    }
}
