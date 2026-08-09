package com.linklife.social.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.linklife.common.core.api.Result;
import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.user.UserSummaryDTO;
import com.linklife.social.client.IdentityUserDirectory;
import com.linklife.social.entity.Follow;
import com.linklife.social.mapper.FollowMapper;
import com.linklife.social.service.IFollowService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * 关注服务（018E 迁移）：目标存在性经 Identity batch(size=1)；
 * 关注/取关/共同关注全部以 MySQL tb_follow 为事实源，不写 Redis。
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private IdentityUserDirectory userDirectory;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (followUserId == null || followUserId <= 0) {
            return Result.fail("关注用户不合法");
        }
        if (isFollow == null) {
            return Result.fail("关注参数不合法");
        }
        if (followUserId.equals(userId)) {
            return Result.fail("不能关注自己");
        }
        if (!userDirectory.existsRequired(followUserId)) {
            return Result.fail("目标用户不存在");
        }
        if (Boolean.TRUE.equals(isFollow)) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            try {
                boolean saved = save(follow);
                if (!saved) {
                    return Result.fail("关注失败，请稍后再试");
                }
            } catch (DuplicateKeyException e) {
                if (!existsFollow(userId, followUserId)) {
                    return Result.fail("关注失败，请稍后再试");
                }
            }
            return Result.ok();
        }
        remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Follow>()
                .eq("user_id", userId).eq("follow_user_id", followUserId));
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserContext.getUserId();
        if (userId == null || followUserId == null) {
            return Result.ok(false);
        }
        return Result.ok(existsFollow(userId, followUserId));
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (id == null || id <= 0) {
            return Result.fail("用户不合法");
        }
        List<Long> commonIds = baseMapper.selectCommonFollowUserIds(userId, id);
        if (commonIds == null || commonIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<UserSummaryDTO> users = userDirectory.orderedRequired(commonIds);
        return Result.ok(users);
    }

    private boolean existsFollow(Long userId, Long followUserId) {
        return count(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Follow>()
                .eq("user_id", userId).eq("follow_user_id", followUserId)) > 0;
    }
}
