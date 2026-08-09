package com.linklife.social.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linklife.social.entity.Blog;
import com.linklife.social.entity.BlogLike;
import com.linklife.social.mapper.BlogLikeMapper;
import com.linklife.social.mapper.BlogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 017J-D 博客点赞事务单元测试：明细与计数同事务、affected 异常回滚、
 * 取消不下溢、博客不存在、唯一冲突不静默、无 Redis 点赞 Key 依赖。
 */
class BlogLikeTransactionalServiceTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Blog.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), BlogLike.class);
    }

    private BlogLikeTransactionalService service;
    private BlogLikeMapper blogLikeMapper;
    private BlogMapper blogMapper;

    @BeforeEach
    void setUp() {
        service = new BlogLikeTransactionalService();
        blogLikeMapper = mock(BlogLikeMapper.class);
        blogMapper = mock(BlogMapper.class);
        ReflectionTestUtils.setField(service, "blogLikeMapper", blogLikeMapper);
        ReflectionTestUtils.setField(service, "blogMapper", blogMapper);
    }

    private BlogLike likeRow(long blogId, long userId) {
        BlogLike like = new BlogLike();
        like.setId(100L + userId);
        like.setBlogId(blogId);
        like.setUserId(userId);
        like.setCreateTime(LocalDateTime.of(2026, 1, 1, 10, 0));
        return like;
    }

    @Test
    void notLikedInsertsDetailAndIncrementsCountInSameCall() {
        when(blogLikeMapper.selectOne(any())).thenReturn(null);
        when(blogLikeMapper.insert(any(BlogLike.class))).thenReturn(1);
        when(blogMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        boolean liked = service.toggleLike(100L, 1L);

        assertThat(liked).isTrue();
        ArgumentCaptor<BlogLike> insertCaptor = ArgumentCaptor.forClass(BlogLike.class);
        verify(blogLikeMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getBlogId()).isEqualTo(100L);
        assertThat(insertCaptor.getValue().getUserId()).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Blog>> updateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(blogMapper).update(org.mockito.ArgumentMatchers.isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).contains("liked = liked + 1");
    }

    @Test
    void likedDeletesDetailAndDecrementsCount() {
        when(blogLikeMapper.selectOne(any())).thenReturn(likeRow(100L, 1L));
        when(blogLikeMapper.delete(any())).thenReturn(1);
        when(blogMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        boolean liked = service.toggleLike(100L, 1L);

        assertThat(liked).isFalse();
        ArgumentCaptor<LambdaQueryWrapper<BlogLike>> deleteCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(blogLikeMapper).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getSqlSegment()).contains("blog_id").contains("user_id");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Blog>> updateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(blogMapper).update(org.mockito.ArgumentMatchers.isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).contains("liked = liked - 1");
        assertThat(updateCaptor.getValue().getSqlSegment()).contains("liked >").contains("id =");
    }

    @Test
    void cancelWithZeroLikedRollsBackWholeToggle() {
        when(blogLikeMapper.selectOne(any())).thenReturn(likeRow(100L, 1L));
        when(blogLikeMapper.delete(any())).thenReturn(1);
        when(blogMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.toggleLike(100L, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("下溢");
        verify(blogLikeMapper).delete(any());
    }

    @Test
    void missingBlogRollsBackWithoutDetailOrCountChange() {
        when(blogLikeMapper.selectOne(any())).thenReturn(null);
        when(blogLikeMapper.insert(any(BlogLike.class))).thenReturn(1);
        when(blogMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.toggleLike(100L, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("博客可能不存在");
    }

    @Test
    void insertAffectedMismatchRollsBack() {
        when(blogLikeMapper.selectOne(any())).thenReturn(null);
        when(blogLikeMapper.insert(any(BlogLike.class))).thenReturn(0);

        assertThatThrownBy(() -> service.toggleLike(100L, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("插入失败");
        verify(blogMapper, never()).update(any(), any());
    }

    @Test
    void deleteAffectedMismatchRollsBack() {
        when(blogLikeMapper.selectOne(any())).thenReturn(likeRow(100L, 1L));
        when(blogLikeMapper.delete(any())).thenReturn(0);

        assertThatThrownBy(() -> service.toggleLike(100L, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("删除失败");
        verify(blogMapper, never()).update(any(), any());
    }

    @Test
    void uniqueConflictIsNotSilentlySucceeded() {
        when(blogLikeMapper.selectOne(any())).thenReturn(null);
        when(blogLikeMapper.insert(any(BlogLike.class)))
                .thenThrow(new DuplicateKeyException("duplicate uk_blog_like"));

        assertThatThrownBy(() -> service.toggleLike(100L, 1L))
                .isInstanceOf(DuplicateKeyException.class);
        verify(blogMapper, never()).update(any(), any());
    }

    @Test
    void transactionalServiceSourceHasNoRedisLikedKey() throws Exception {
        String source = Files.readString(
                Paths.get("src/main/java/com/linklife/social/service/impl/BlogLikeTransactionalService.java"),
                StandardCharsets.UTF_8);
        assertThat(source)
                .doesNotContain("StringRedisTemplate")
                .doesNotContain("blog:liked:");
    }
}
