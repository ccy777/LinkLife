package com.linklife.trade.submission;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.mapper.VoucherOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderCreationFailureService 单元测试：终态事实分类（A/B/C/D）与补偿编排。
 */
class OrderCreationFailureServiceTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), VoucherOrder.class);
    }

    private OrderCreationFailureService service;
    private VoucherOrderMapper orderMapper;
    private OrderCreateFailureCompensationAdapter compensationAdapter;

    @BeforeEach
    void setUp() {
        service = new OrderCreationFailureService();
        orderMapper = mock(VoucherOrderMapper.class);
        compensationAdapter = mock(OrderCreateFailureCompensationAdapter.class);
        ReflectionTestUtils.setField(service, "voucherOrderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "compensationAdapter", compensationAdapter);
    }

    private VoucherOrder message(long orderId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(1L);
        order.setVoucherId(2L);
        return order;
    }

    private VoucherOrder existing(long orderId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        return order;
    }

    private void stubSuccess() {
        when(compensationAdapter.compensate(any(OrderCreateFailureCompensationCommand.class)))
                .thenReturn(OrderCreateFailureCompensationResult.success());
    }

    @Test
    void currentOrderExistsWithSameIdentityIsPersistedWithoutCompensation() {
        VoucherOrder byId = new VoucherOrder();
        byId.setId(1001L);
        byId.setUserId(1L);
        byId.setVoucherId(2L);
        when(orderMapper.selectOne(any())).thenReturn(byId);

        OrderCreationFailureDecision decision = service.classifyAndCompensate(message(1001L));

        assertThat(decision.type())
                .isEqualTo(OrderCreationFailureDecision.DecisionType.CURRENT_ORDER_PERSISTED);
        verify(compensationAdapter, never()).compensate(any(OrderCreateFailureCompensationCommand.class));
    }

    @Test
    void orderIdExistsWithIdentityMismatchIsUncertain() {
        VoucherOrder byId = new VoucherOrder();
        byId.setId(1001L);
        byId.setUserId(999L);
        byId.setVoucherId(2L);
        when(orderMapper.selectOne(any())).thenReturn(byId);

        OrderCreationFailureDecision decision = service.classifyAndCompensate(message(1001L));

        assertThat(decision.type()).isEqualTo(OrderCreationFailureDecision.DecisionType.UNCERTAIN);
        verify(compensationAdapter, never()).compensate(any(OrderCreateFailureCompensationCommand.class));
    }

    @Test
    void noMySqlOrderCompensatesReleaseMode() {
        when(orderMapper.selectOne(any())).thenReturn(null, null);
        stubSuccess();

        OrderCreationFailureDecision decision = service.classifyAndCompensate(message(1001L));

        assertThat(decision.type()).isEqualTo(OrderCreationFailureDecision.DecisionType.NO_MYSQL_ORDER);
        ArgumentCaptor<OrderCreateFailureCompensationCommand> captor =
                ArgumentCaptor.forClass(OrderCreateFailureCompensationCommand.class);
        verify(compensationAdapter).compensate(captor.capture());
        assertThat(captor.getValue().mode())
                .isEqualTo(OrderCreateCompensationMode.RESTORE_STOCK_AND_RELEASE_QUALIFICATION);
        assertThat(captor.getValue().existingOrderId()).isZero();
    }

    @Test
    void conflictingOtherOrderCompensatesKeepMode() {
        when(orderMapper.selectOne(any())).thenReturn(null, existing(9999L));
        stubSuccess();

        OrderCreationFailureDecision decision = service.classifyAndCompensate(message(1001L));

        assertThat(decision.type()).isEqualTo(OrderCreationFailureDecision.DecisionType.CONFLICTING_OTHER_ORDER);
        ArgumentCaptor<OrderCreateFailureCompensationCommand> captor =
                ArgumentCaptor.forClass(OrderCreateFailureCompensationCommand.class);
        verify(compensationAdapter).compensate(captor.capture());
        assertThat(captor.getValue().mode())
                .isEqualTo(OrderCreateCompensationMode.RESTORE_STOCK_KEEP_QUALIFICATION);
        assertThat(captor.getValue().existingOrderId()).isEqualTo(9999L);
    }

    @Test
    void retryableCompensationMapsToRetryableDecision() {
        when(orderMapper.selectOne(any())).thenReturn(null, null);
        when(compensationAdapter.compensate(any(OrderCreateFailureCompensationCommand.class)))
                .thenReturn(OrderCreateFailureCompensationResult.retryable("CREATE_COMP_ACCESS_FAILED"));

        assertThat(service.classifyAndCompensate(message(1001L)).type())
                .isEqualTo(OrderCreationFailureDecision.DecisionType.RETRYABLE_COMPENSATION);
    }

    @Test
    void fatalCompensationMapsToFatalDecision() {
        when(orderMapper.selectOne(any())).thenReturn(null, null);
        when(compensationAdapter.compensate(any(OrderCreateFailureCompensationCommand.class)))
                .thenReturn(OrderCreateFailureCompensationResult.fatal("CREATE_COMP_MARKER_CORRUPT"));

        assertThat(service.classifyAndCompensate(message(1001L)).type())
                .isEqualTo(OrderCreationFailureDecision.DecisionType.FATAL_COMPENSATION);
    }

    @Test
    void dbReadFailureOnOrderIdIsUncertainWithoutCompensation() {
        when(orderMapper.selectOne(any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(service.classifyAndCompensate(message(1001L)).type())
                .isEqualTo(OrderCreationFailureDecision.DecisionType.UNCERTAIN);
        verify(compensationAdapter, never()).compensate(any(OrderCreateFailureCompensationCommand.class));
    }

    @Test
    void dbReadFailureOnUserVoucherIsUncertainWithoutCompensation() {
        when(orderMapper.selectOne(any())).thenReturn(null)
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(service.classifyAndCompensate(message(1001L)).type())
                .isEqualTo(OrderCreationFailureDecision.DecisionType.UNCERTAIN);
        verify(compensationAdapter, never()).compensate(any(OrderCreateFailureCompensationCommand.class));
    }
}
