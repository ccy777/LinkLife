package com.linklife.identity.internal;

import com.linklife.common.core.user.UserSummaryDTO;
import com.linklife.common.core.user.UserSummaryRequest;
import com.linklife.identity.entity.User;
import com.linklife.identity.service.IUserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Set;

/**
 * Identity 内部批量用户摘要 API（仅内部网络，Gateway 不暴露 /api/internal/**）。
 *
 * <p>语义：空请求/空集合 → []；非法 id（null/&lt;=0）→ 400 且不查 DB；
 * 正常 Set → 一次 MySQL listByIds，只返回真实存在用户；部分/全部缺失 → 只返回存在项。
 * 返回顺序不作为 RPC 契约，Social 按自身原始顺序重新组装。</p>
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    @Resource
    private IUserService userService;

    @PostMapping("/batch")
    public List<UserSummaryDTO> batch(@RequestBody(required = false) UserSummaryRequest request) {
        if (request == null || request.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = request.userIds();
        for (Long id : userIds) {
            if (id == null || id <= 0L) {
                throw new IllegalArgumentException("非法用户 ID");
            }
        }
        // 单次 DB batch（listByIds 一次 IN 查询）
        List<User> users = userService.listByIds(userIds);
        return users.stream()
                .map(u -> new UserSummaryDTO(u.getId(), u.getNickName(), u.getIcon()))
                .toList();
    }
}
