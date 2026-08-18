package com.linklife.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linklife.common.core.api.Result;
import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.common.core.user.UserSummaryDTO;
import com.linklife.social.client.IdentityUserDirectory;
import com.linklife.social.dto.ScrollResult;
import com.linklife.social.entity.Blog;
import com.linklife.social.entity.BlogLike;
import com.linklife.social.mapper.BlogLikeMapper;
import com.linklife.social.mapper.BlogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 博客服务（018E）：作者回填单次 batch（消除远程 N+1）、UserContext、点赞/保存/关注流语义。
 */
class BlogServiceImplTest {

    private BlogServiceImpl service;
    private IdentityUserDirectory userDirectory;
    private BlogMapper blogMapper;
    private BlogLikeMapper blogLikeMapper;
    private RedissonClient redissonClient;
    private RLock lock;
    private BlogLikeTransactionalService likeTransactionalService;

    @BeforeEach
    void setUp() throws Exception {
        service = spy(new BlogServiceImpl());
        userDirectory = mock(IdentityUserDirectory.class);
        blogMapper = mock(BlogMapper.class);
        blogLikeMapper = mock(BlogLikeMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        likeTransactionalService = mock(BlogLikeTransactionalService.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        ReflectionTestUtils.setField(service, "userDirectory", userDirectory);
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(service, "blogLikeMapper", blogLikeMapper);
        ReflectionTestUtils.setField(service, "blogLikeTransactionalService", likeTransactionalService);
        ReflectionTestUtils.setField(service, "baseMapper", blogMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private UserSummaryDTO summary(long id, String nickName) {
        return new UserSummaryDTO(id, nickName, "icon" + id);
    }

    private Blog blog(long id, Long userId, int liked, LocalDateTime createTime) {
        Blog b = new Blog();
        b.setId(id);
        b.setUserId(userId);
        b.setLiked(liked);
        b.setCreateTime(createTime);
        return b;
    }

    private Page<Blog> pageOf(List<Blog> records) {
        Page<Blog> page = new Page<>(1, 10, records.size());
        page.setRecords(records);
        return page;
    }

    @Test
    void hotBlogFillsAuthorsWithSingleBatchRpc() {
        List<Blog> records = List.of(blog(1L, 1L, 3, LocalDateTime.now()),
                blog(2L, 2L, 2, LocalDateTime.now()),
                blog(3L, 1L, 1, LocalDateTime.now()));
        when(blogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(pageOf(records));
        Map<Long, UserSummaryDTO> byId = new LinkedHashMap<>();
        byId.put(1L, summary(1L, "u1"));
        byId.put(2L, summary(2L, "u2"));
        when(userDirectory.batchForDisplay(anyCollection())).thenReturn(byId);

        Result result = service.queryHotBlog(1);

        assertThat(result.getSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Blog> data = (List<Blog>) result.getData();
        assertThat(data).hasSize(3);
        assertThat(data.get(0).getName()).isEqualTo("u1");
        assertThat(data.get(1).getName()).isEqualTo("u2");
        verify(userDirectory, times(1)).batchForDisplay(anyCollection());
    }

    @Test
    void hotBlogMarkedLikeForCurrentUser() {
        List<Blog> records = List.of(blog(1L, 1L, 3, LocalDateTime.now()));
        when(blogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(pageOf(records));
        when(userDirectory.batchForDisplay(anyCollection())).thenReturn(Map.of(1L, summary(1L, "u1")));
        UserContext.set(9L);
        when(blogLikeMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        Result result = service.queryHotBlog(1);

        @SuppressWarnings("unchecked")
        List<Blog> data = (List<Blog>) result.getData();
        assertThat(data.get(0).getIsLike()).isTrue();
    }

    @Test
    void blogByIdUsesAtMostOneBatchAndMissingUserDoesNotNpe() {
        Blog b = blog(1L, 99L, 3, LocalDateTime.now());
        when(service.getById(1L)).thenReturn(b);
        when(userDirectory.batchForDisplay(anyCollection())).thenReturn(Map.of());

        Result result = service.queryBlogById(1L);

        Blog data = (Blog) result.getData();
        assertThat(data.getName()).isNull();
        assertThat(data.getIcon()).isNull();
        verify(userDirectory, times(1)).batchForDisplay(anyCollection());
    }

    @Test
    void blogLikesTop5SingleBatchOriginalOrder() {
        when(blogLikeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(like(1L, 5L), like(2L, 2L), like(3L, 5L)));
        when(userDirectory.orderedRequired(List.of(5L, 2L, 5L)))
                .thenReturn(List.of(summary(5L, "five"), summary(2L, "two")));

        Result result = service.queryBlogLikes(1L);

        @SuppressWarnings("unchecked")
        List<UserSummaryDTO> data = (List<UserSummaryDTO>) result.getData();
        assertThat(data).extracting(UserSummaryDTO::id).containsExactly(5L, 2L);
        verify(userDirectory, times(1)).orderedRequired(anyCollection());
    }

    @Test
    void hotBlogDisplayDegradesWithoutFakeUsers() {
        List<Blog> records = List.of(blog(1L, 1L, 3, LocalDateTime.now()));
        when(blogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(pageOf(records));
        // Identity 不可用 → display 返回空 map → Blog 主体仍 200，name/icon 不填、不伪造。
        when(userDirectory.batchForDisplay(anyCollection())).thenReturn(Map.of());

        Result result = service.queryHotBlog(1);

        assertThat(result.getSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Blog> data = (List<Blog>) result.getData();
        assertThat(data).hasSize(1);
        assertThat(data.get(0).getId()).isEqualTo(1L);
        assertThat(data.get(0).getUserId()).isEqualTo(1L);
        assertThat(data.get(0).getName()).isNull();
        assertThat(data.get(0).getIcon()).isNull();
    }

    @Test
    void blogLikesFailsClosedWhenIdentityUnavailable() {
        when(blogLikeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(like(1L, 5L), like(2L, 2L)));
        when(userDirectory.orderedRequired(anyCollection()))
                .thenThrow(new BusinessException("用户服务暂时不可用，请稍后再试"));

        assertThatThrownBy(() -> service.queryBlogLikes(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户服务暂时不可用，请稍后再试");
    }

    @Test
    void likeWithoutLoginFailsBeforeLock() {
        Result result = service.likeBlog(1L);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请先登录");
        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    void likeWithUserTogglesAndUnlocks() throws Exception {
        UserContext.set(7L);
        Result result = service.likeBlog(1L);
        assertThat(result.getSuccess()).isTrue();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redissonClient).getLock(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("social:lock:blog:like:1:7");
        verify(likeTransactionalService).toggleLike(1L, 7L);
        verify(lock).unlock();
    }

    @Test
    void saveBlogWithoutLoginFails() {
        Result result = service.saveBlog(new Blog());
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请先登录");
    }

    @Test
    void saveBlogSetsCurrentUserIdAndSaves() {
        UserContext.set(3L);
        Blog blog = new Blog();
        when(service.save(blog)).thenReturn(true);
        blog.setId(42L);
        Result result = service.saveBlog(blog);
        assertThat(result.getSuccess()).isTrue();
        assertThat(blog.getUserId()).isEqualTo(3L);
    }

    @Test
    void isBlogLikedAnonymousSkipsMapper() {
        Blog b = blog(1L, 1L, 1, LocalDateTime.now());
        when(service.getById(1L)).thenReturn(b);
        when(userDirectory.batchForDisplay(anyCollection())).thenReturn(Map.of());
        Result result = service.queryBlogById(1L);
        assertThat(result.getSuccess()).isTrue();
        // 匿名：isLike 保持 null，不触发点赞查询
        assertThat(b.getIsLike()).isNull();
        verify(blogLikeMapper, never()).selectCount(any(Wrapper.class));
    }

    @Test
    void followFeedUsesSingleBatchRpcAndScrollPagination() {
        LocalDateTime now = LocalDateTime.now();
        List<Blog> feed = List.of(blog(1L, 1L, 0, now), blog(2L, 2L, 0, now.minusMinutes(1)));
        when(blogMapper.selectFollowFeed(anyLong(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(feed);
        Map<Long, UserSummaryDTO> byId = new LinkedHashMap<>();
        byId.put(1L, summary(1L, "u1"));
        byId.put(2L, summary(2L, "u2"));
        when(userDirectory.batchForDisplay(anyCollection())).thenReturn(byId);
        UserContext.set(1L);

        Result result = service.queryBlogOfFollow(9999999999999L, 0);

        assertThat(result.getSuccess()).isTrue();
        ScrollResult scroll = (ScrollResult) result.getData();
        assertThat(scroll.getList()).hasSize(2);
        verify(userDirectory, times(1)).batchForDisplay(anyCollection());
    }

    private BlogLike like(long id, long userId) {
        BlogLike l = new BlogLike();
        l.setId(id);
        l.setBlogId(1L);
        l.setUserId(userId);
        return l;
    }

}
