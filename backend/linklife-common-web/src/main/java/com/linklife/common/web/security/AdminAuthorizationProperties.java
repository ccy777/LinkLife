package com.linklife.common.web.security;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 管理写接口授权配置（017J-C 语义迁移至 common-web）。
 *
 * <p>前缀 {@code linklife.security.admin-user-ids}（环境变量 {@code LINKLIFE_ADMIN_USER_IDS}），
 * 逗号分隔的正整数用户 ID；空集合表示没有管理员，所有受保护管理写接口默认拒绝。
 * 非正整数或无法解析的配置在启动时失败；不记录完整管理员 ID 列表；不暴露内部可变集合。</p>
 */
@Component
@ConfigurationProperties(prefix = "linklife.security")
public class AdminAuthorizationProperties {

    private Set<Long> adminUserIds = new HashSet<>();

    @PostConstruct
    void validate() {
        for (Long id : adminUserIds) {
            if (id == null || id <= 0) {
                throw new IllegalStateException("linklife.security.admin-user-ids 只允许正整数");
            }
        }
    }

    public void setAdminUserIds(Set<Long> adminUserIds) {
        this.adminUserIds = adminUserIds == null ? new HashSet<>() : new HashSet<>(adminUserIds);
    }

    public boolean isAdmin(Long userId) {
        return userId != null && adminUserIds.contains(userId);
    }

    Set<Long> adminUserIdsView() {
        return Collections.unmodifiableSet(adminUserIds);
    }
}
