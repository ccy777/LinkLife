package com.linklife.social.client;

import com.linklife.common.core.user.UserSummaryDTO;
import com.linklife.common.core.user.UserSummaryRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

/**
 * Social → Identity 正式批量用户摘要 Feign Client（018E）。
 *
 * <p>name 必须与 Nacos serviceId 完全一致（linklife-identity-service）；contextId 仅作 Spring bean 标识。</p>
 */
@FeignClient(
        name = "linklife-identity-service",
        contextId = "identityUserClient",
        path = "/internal/users"
)
public interface IdentityUserClient {

    @PostMapping("/batch")
    List<UserSummaryDTO> batch(UserSummaryRequest request);
}
