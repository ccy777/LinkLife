package com.linklife.transaction;

import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.common.core.api.Result;
import com.linklife.trade.admission.RedisSeckillAdmissionAdapter;
import com.linklife.trade.application.OrderLifecycleService;
import com.linklife.trade.application.OrderQueryService;
import com.linklife.trade.application.OrderSubmissionStatusService;
import com.linklife.trade.mapper.VoucherOrderMapper;
import com.linklife.trade.service.impl.VoucherOrderServiceImpl;
import com.linklife.trade.submission.RedisOrderSubmissionStatusRepository;
import com.linklife.shared.redis.RedisIdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * UserContext fail-closed：4 个原 UserHolder 消费点在无用户上下文时稳定失败、不 NPE。
 */
class TransactionUserContextContractTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void seckillVoucherWithoutUserContextFailsClosed() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "redisIdWorker", mock(RedisIdWorker.class));
        ReflectionTestUtils.setField(service, "seckillAdmissionAdapter", mock(RedisSeckillAdmissionAdapter.class));
        Result result = service.seckillVoucher(1L);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请先登录");
    }

    @Test
    void orderQueryWithoutUserContextFailsClosed() {
        OrderQueryService service = new OrderQueryService();
        ReflectionTestUtils.setField(service, "voucherOrderMapper", mock(VoucherOrderMapper.class));
        assertThatThrownBy(() -> service.getOrder(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先登录");
    }

    @Test
    void submissionQueryWithoutUserContextFailsClosed() {
        OrderSubmissionStatusService service = new OrderSubmissionStatusService();
        ReflectionTestUtils.setField(service, "voucherOrderMapper", mock(VoucherOrderMapper.class));
        ReflectionTestUtils.setField(service, "submissionStatusRepository",
                mock(RedisOrderSubmissionStatusRepository.class));
        assertThatThrownBy(() -> service.getSubmissionStatus(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先登录");
    }

    @Test
    void cancelWithoutUserContextFailsClosed() {
        OrderLifecycleService service = new OrderLifecycleService();
        assertThatThrownBy(() -> service.cancelByCurrentUser(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先登录");
    }
}
