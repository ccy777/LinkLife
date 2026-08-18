package com.linklife.common.core.user;

/**
 * Identity 内部批量用户摘要响应元素（018E 冻结契约）。
 *
 * <p>只允许 id / nickName / icon；不得包含 phone / password / createTime / updateTime /
 * 任何认证信息。纯 DTO，common-core 不依赖 Servlet/WebFlux/Redis/MyBatis/Feign/业务 Entity。</p>
 */
public record UserSummaryDTO(Long id, String nickName, String icon) {
}
