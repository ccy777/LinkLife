package com.linklife.social.client;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.common.core.user.UserSummaryDTO;
import com.linklife.common.core.user.UserSummaryRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IdentityUserDirectory（Stage 5A）：display 可降级但记录 Sentinel 失败；
 * required fail-closed；真实 missing 与“服务不可用”严格区分；breaker OPEN fast-fail 与恢复。
 */
class IdentityUserDirectoryTest {

    private IdentityUserClient client;
    private IdentityUserDirectory directory;

    @BeforeEach
    void setUp() {
        client = mock(IdentityUserClient.class);
        directory = new IdentityUserDirectory(client);
        DegradeRuleManager.loadRules(List.of());
    }

    @AfterEach
    void clearSentinelGlobalState() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void emptyOrNullIdsDoesNotInvokeRpc() {
        assertThat(directory.batchForDisplay(null)).isEmpty();
        assertThat(directory.batchForDisplay(List.of())).isEmpty();
        assertThat(directory.batchForDisplay(Arrays.asList(null, 0L, -1L))).isEmpty();
        assertThat(directory.orderedRequired(null)).isEmpty();
        assertThat(directory.orderedRequired(List.of())).isEmpty();
        verify(client, never()).batch(any());
    }

    @Test
    void duplicateIdsDeduplicatedIntoOneRpc() {
        when(client.batch(any())).thenReturn(List.of(
                new UserSummaryDTO(1L, "one", "i1"),
                new UserSummaryDTO(2L, "two", "i2")));
        Map<Long, UserSummaryDTO> result =
                directory.batchForDisplay(Arrays.asList(1L, 2L, 1L, 2L));
        assertThat(result.keySet()).containsExactlyInAnyOrder(1L, 2L);
        verify(client, times(1)).batch(any(UserSummaryRequest.class));
    }

    @Test
    void displayDegradesToEmptyMapOnIdentityFailure() {
        when(client.batch(any())).thenThrow(new IllegalStateException("identity down"));
        Map<Long, UserSummaryDTO> result = directory.batchForDisplay(List.of(1L));
        assertThat(result).isEmpty();
    }

    @Test
    void displayDoesNotFakeUsersWhenResponseMissing() {
        when(client.batch(any())).thenReturn(List.of());
        assertThat(directory.batchForDisplay(List.of(1L))).isEmpty();
        when(client.batch(any())).thenReturn(List.of(new UserSummaryDTO(1L, "one", "i1")));
        assertThat(directory.batchForDisplay(List.of(1L))).containsKey(1L);
    }

    @Test
    void requiredOrderedFailsClosedOnIdentityFailure() {
        when(client.batch(any())).thenThrow(new IllegalStateException("identity down"));
        assertThatThrownBy(() -> directory.orderedRequired(List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户服务暂时不可用，请稍后再试");
    }

    @Test
    void existsRequiredFailsClosedOnIdentityFailure() {
        when(client.batch(any())).thenThrow(new IllegalStateException("identity down"));
        assertThatThrownBy(() -> directory.existsRequired(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户服务暂时不可用，请稍后再试");
    }

    @Test
    void existsRequiredFalseOnlyForRealMissing() {
        when(client.batch(any())).thenReturn(List.of(new UserSummaryDTO(1L, "one", "i1")));
        assertThat(directory.existsRequired(1L)).isTrue();
        when(client.batch(any())).thenReturn(List.of());
        assertThat(directory.existsRequired(999L)).isFalse();
    }

    @Test
    void orderedRequiredPreservesOrderAndSkipsMissing() {
        when(client.batch(any())).thenReturn(List.of(
                new UserSummaryDTO(5L, "five", "i5"),
                new UserSummaryDTO(2L, "two", "i2")));
        List<UserSummaryDTO> result = directory.orderedRequired(Arrays.asList(5L, 99L, 2L));
        assertThat(result).extracting(UserSummaryDTO::id).containsExactly(5L, 2L);
    }

    @Test
    void breakerOpenStopsFeignCallsForDisplayAndRequired() {
        installLowThresholdDegradeRule();
        AtomicInteger calls = new AtomicInteger();
        when(client.batch(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            throw new IllegalStateException("identity down");
        });

        assertThat(directory.batchForDisplay(List.of(1L))).isEmpty();
        assertThat(directory.batchForDisplay(List.of(1L))).isEmpty();
        assertThat(calls.get()).isEqualTo(2);

        // 连续失败达到阈值 → circuit OPEN：display 继续返回 {}，但 Feign 调用次数不再增长。
        assertThat(directory.batchForDisplay(List.of(1L))).isEmpty();
        assertThat(directory.batchForDisplay(List.of(2L))).isEmpty();
        assertThat(calls.get()).isEqualTo(2);

        // required 模式在 OPEN 时同样 fast-fail 为业务不可用，不落空列表/false。
        assertThatThrownBy(() -> directory.orderedRequired(List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户服务暂时不可用，请稍后再试");
        assertThatThrownBy(() -> directory.existsRequired(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户服务暂时不可用，请稍后再试");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void breakerRecoversAfterTimeWindow() throws Exception {
        installLowThresholdDegradeRule();
        AtomicInteger calls = new AtomicInteger();
        when(client.batch(any())).thenAnswer(invocation -> {
            int n = calls.incrementAndGet();
            if (n <= 2) {
                throw new IllegalStateException("identity down");
            }
            return List.of(new UserSummaryDTO(1L, "one", "i1"));
        });

        assertThat(directory.batchForDisplay(List.of(1L))).isEmpty();
        assertThat(directory.batchForDisplay(List.of(1L))).isEmpty();
        assertThat(calls.get()).isEqualTo(2);

        // 等待 timeWindow → half-open probe。
        Thread.sleep(1100);
        Map<Long, UserSummaryDTO> probe = directory.batchForDisplay(List.of(1L));
        assertThat(probe).containsKey(1L);
        assertThat(calls.get()).isEqualTo(3);

        // probe 成功 → CLOSED，正常 RPC 恢复。
        Map<Long, UserSummaryDTO> after = directory.batchForDisplay(List.of(1L));
        assertThat(after).containsKey(1L);
        assertThat(calls.get()).isEqualTo(4);
    }

    private static void installLowThresholdDegradeRule() {
        DegradeRule rule = new DegradeRule(IdentityUserDirectory.RESOURCE_NAME)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(2)
                .setStatIntervalMs(1000)
                .setTimeWindow(1);
        DegradeRuleManager.loadRules(List.of(rule));
    }
}
