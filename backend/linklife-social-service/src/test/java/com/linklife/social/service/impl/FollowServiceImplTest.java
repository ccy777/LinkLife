package com.linklife.social.service.impl;

import com.linklife.common.core.api.Result;
import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.common.core.user.UserSummaryDTO;
import com.linklife.social.client.IdentityUserDirectory;
import com.linklife.social.entity.Follow;
import com.linklife.social.mapper.FollowMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关注服务（018E）：目标存在性 batch(size=1)、RPC 失败≠目标缺失、共同关注单次 batch、幂等/自关注/取关语义。
 */
class FollowServiceImplTest {

    private FollowServiceImpl service;
    private IdentityUserDirectory userDirectory;
    private FollowMapper followMapper;

    @BeforeEach
    void setUp() {
        service = spy(new FollowServiceImpl());
        userDirectory = mock(IdentityUserDirectory.class);
        followMapper = mock(FollowMapper.class);
        ReflectionTestUtils.setField(service, "userDirectory", userDirectory);
        ReflectionTestUtils.setField(service, "baseMapper", followMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void followWithoutLoginFails() {
        Result result = service.follow(2L, true);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请先登录");
        verify(userDirectory, never()).existsRequired(anyLong());
    }

    @Test
    void selfFollowRejected() {
        UserContext.set(2L);
        Result result = service.follow(2L, true);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("不能关注自己");
        verify(userDirectory, never()).existsRequired(anyLong());
    }

    @Test
    void followTargetMissingViaBatchSizeOne() {
        UserContext.set(1L);
        when(userDirectory.existsRequired(2L)).thenReturn(false);
        Result result = service.follow(2L, true);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("目标用户不存在");
        verify(userDirectory, times(1)).existsRequired(2L);
        verify(service, never()).save(any(Follow.class));
    }

    @Test
    void rpcFailureIsNotTreatedAsMissing() {
        UserContext.set(1L);
        when(userDirectory.existsRequired(2L)).thenThrow(
                new BusinessException("用户服务暂时不可用，请稍后再试"));
        assertThatThrownBy(() -> service.follow(2L, true))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户服务暂时不可用，请稍后再试");
    }

    @Test
    void followSuccessInsertsRow() {
        UserContext.set(1L);
        when(userDirectory.existsRequired(2L)).thenReturn(true);
        when(service.save(any(Follow.class))).thenReturn(true);
        Result result = service.follow(2L, true);
        assertThat(result.getSuccess()).isTrue();
    }

    @Test
    void duplicateFollowIsIdempotent() {
        UserContext.set(1L);
        when(userDirectory.existsRequired(2L)).thenReturn(true);
        when(service.save(any(Follow.class))).thenThrow(new DuplicateKeyException("dup"));
        when(service.count(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);
        Result result = service.follow(2L, true);
        assertThat(result.getSuccess()).isTrue();
    }

    @Test
    void repeatUnfollowIsIdempotent() {
        UserContext.set(1L);
        when(userDirectory.existsRequired(2L)).thenReturn(true);
        when(service.remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(true);
        Result result = service.follow(2L, false);
        assertThat(result.getSuccess()).isTrue();
    }

    @Test
    void isFollowTrueAndFalse() {
        UserContext.set(1L);
        when(service.count(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);
        assertThat(service.isFollow(2L).getData()).isEqualTo(true);
        when(service.count(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0L);
        assertThat(service.isFollow(2L).getData()).isEqualTo(false);
    }

    @Test
    void isFollowAnonymousReturnsFalse() {
        assertThat(service.isFollow(2L).getData()).isEqualTo(false);
    }

    @Test
    void commonFollowsSingleBatchAndPreservesOrder() {
        UserContext.set(1L);
        when(followMapper.selectCommonFollowUserIds(1L, 2L)).thenReturn(Arrays.asList(5L, 2L));
        when(userDirectory.orderedRequired(Arrays.asList(5L, 2L)))
                .thenReturn(Arrays.asList(new UserSummaryDTO(5L, "five", "i5"),
                        new UserSummaryDTO(2L, "two", "i2")));

        Result result = service.followCommons(2L);

        @SuppressWarnings("unchecked")
        List<UserSummaryDTO> data = (List<UserSummaryDTO>) result.getData();
        assertThat(data).extracting(UserSummaryDTO::id).containsExactly(5L, 2L);
        verify(userDirectory, times(1)).orderedRequired(anyCollection());
    }

    @Test
    void commonFollowsEmptyReturnsEmptyWithoutRpc() {
        UserContext.set(1L);
        when(followMapper.selectCommonFollowUserIds(1L, 2L)).thenReturn(Collections.emptyList());
        Result result = service.followCommons(2L);
        assertThat(result.getSuccess()).isTrue();
        verify(userDirectory, never()).orderedRequired(anyCollection());
    }
}
