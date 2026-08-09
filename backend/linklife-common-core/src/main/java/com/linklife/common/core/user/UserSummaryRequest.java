package com.linklife.common.core.user;

import java.util.Set;

/**
 * Identity 内部批量用户摘要请求（018E 冻结契约）。
 *
 * <p>null set 归一化为空集；实际调用方只发送去重后的正 Long；
 * Identity 对 null / &lt;=0 id 稳定拒绝 400，不静默查询异常 id。</p>
 */
public record UserSummaryRequest(Set<Long> userIds) {

    public UserSummaryRequest {
        userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
    }

    public boolean isEmpty() {
        return userIds.isEmpty();
    }
}
