package com.linklife.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.linklife.common.core.api.Result;
import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.user.UserSummaryDTO;
import com.linklife.common.core.util.SystemConstants;
import com.linklife.social.client.IdentityUserDirectory;
import com.linklife.social.dto.ScrollResult;
import com.linklife.social.entity.Blog;
import com.linklife.social.entity.BlogLike;
import com.linklife.social.mapper.BlogLikeMapper;
import com.linklife.social.mapper.BlogMapper;
import com.linklife.social.service.IBlogService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 博客服务（018E 迁移）：作者信息统一经 {@link IdentityUserDirectory} 批量获取，
 * 同一请求内 Identity RPC 至多 1 次，消除远程 N+1；MySQL 为点赞/关注流事实源。
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int FOLLOW_FEED_PAGE_SIZE = 2;
    private static final int LIKE_TOP_N = 5;
    private static final long LIKE_LOCK_WAIT_SECONDS = 2L;
    private static final String LIKE_LOCK_KEY_PREFIX = "social:lock:blog:like:";

    @Resource
    private IdentityUserDirectory userDirectory;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private BlogLikeMapper blogLikeMapper;

    @Resource
    private BlogLikeTransactionalService blogLikeTransactionalService;

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        Map<Long, UserSummaryDTO> byId = userDirectory.batchForDisplay(
                records.stream().map(Blog::getUserId).collect(Collectors.toList()));
        for (Blog blog : records) {
            fillBlogUser(blog, byId);
            isBlogLiked(blog);
        }
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        Map<Long, UserSummaryDTO> byId = userDirectory.batchForDisplay(
                blog.getUserId() == null ? Collections.emptyList() : List.of(blog.getUserId()));
        fillBlogUser(blog, byId);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return;
        }
        Long likedCount = blogLikeMapper.selectCount(new LambdaQueryWrapper<BlogLike>()
                .eq(BlogLike::getBlogId, blog.getId())
                .eq(BlogLike::getUserId, userId));
        blog.setIsLike(likedCount != null && likedCount > 0);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (id == null || id <= 0) {
            return Result.fail("博客不合法");
        }
        RLock lock = redissonClient.getLock(LIKE_LOCK_KEY_PREFIX + id + ":" + userId);
        boolean locked = false;
        try {
            locked = lock.tryLock(LIKE_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return Result.fail("操作繁忙，请稍后再试");
            }
            blogLikeTransactionalService.toggleLike(id, userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("操作繁忙，请稍后再试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        if (id == null || id <= 0) {
            return Result.ok(Collections.emptyList());
        }
        List<BlogLike> topLikes = blogLikeMapper.selectList(new LambdaQueryWrapper<BlogLike>()
                .eq(BlogLike::getBlogId, id)
                .orderByAsc(BlogLike::getCreateTime, BlogLike::getId)
                .last("LIMIT " + LIKE_TOP_N));
        if (topLikes == null || topLikes.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = topLikes.stream().map(BlogLike::getUserId).collect(Collectors.toList());
        List<UserSummaryDTO> users = userDirectory.orderedRequired(ids);
        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        blog.setUserId(userId);
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败!");
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        long maxTimeMillis;
        if (max == null) {
            maxTimeMillis = System.currentTimeMillis();
        } else if (max <= 0) {
            return Result.fail("分页参数不合法");
        } else {
            maxTimeMillis = max;
        }
        LocalDateTime maxTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(maxTimeMillis), ZoneId.systemDefault());
        int os = (offset == null || offset < 0) ? 0 : offset;
        List<Blog> blogs = baseMapper.selectFollowFeed(userId, maxTime, os, FOLLOW_FEED_PAGE_SIZE);
        if (blogs == null || blogs.isEmpty()) {
            ScrollResult empty = new ScrollResult();
            empty.setList(Collections.emptyList());
            empty.setMinTime(maxTimeMillis);
            empty.setOffset(0);
            return Result.ok(empty);
        }
        long minTime = maxTimeMillis;
        int pageOffset = 0;
        for (Blog blog : blogs) {
            LocalDateTime createTime = blog.getCreateTime();
            long time = createTime == null ? maxTimeMillis
                    : createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (time == minTime) {
                pageOffset++;
            } else {
                minTime = time;
                pageOffset = 1;
            }
        }
        Map<Long, UserSummaryDTO> byId = userDirectory.batchForDisplay(
                blogs.stream().map(Blog::getUserId).collect(Collectors.toList()));
        for (Blog blog : blogs) {
            fillBlogUser(blog, byId);
            isBlogLiked(blog);
        }
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(pageOffset);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void fillBlogUser(Blog blog, Map<Long, UserSummaryDTO> byId) {
        Long userId = blog.getUserId();
        if (userId == null) {
            return;
        }
        UserSummaryDTO user = byId.get(userId);
        if (user == null) {
            return;
        }
        blog.setName(user.nickName());
        blog.setIcon(user.icon());
    }
}
