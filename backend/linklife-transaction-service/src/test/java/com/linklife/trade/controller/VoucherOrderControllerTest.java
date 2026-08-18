package com.linklife.trade.controller;

import com.linklife.common.core.api.Result;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.common.web.exception.GlobalExceptionHandler;
import com.linklife.trade.application.OrderLifecycleService;
import com.linklife.trade.application.OrderQueryService;
import com.linklife.trade.application.OrderSubmissionStatusService;
import com.linklife.trade.dto.OrderSubmissionStatusDTO;
import com.linklife.trade.dto.VoucherOrderDTO;
import com.linklife.trade.dto.VoucherOrderPageDTO;
import com.linklife.trade.service.IVoucherOrderService;
import com.linklife.trade.submission.OrderSubmissionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VoucherOrderController 单元测试：standalone MockMvc，四个接口映射、静态路径优先级、
 * 参数边界、旧 POST 契约不变。不启动 Spring 上下文、不连接 Redis/MySQL。
 */
class VoucherOrderControllerTest {

    private MockMvc mockMvc;
    private IVoucherOrderService voucherOrderService;
    private OrderSubmissionStatusService submissionStatusService;
    private OrderQueryService orderQueryService;
    private OrderLifecycleService orderLifecycleService;

    @BeforeEach
    void setUp() {
        voucherOrderService = mock(IVoucherOrderService.class);
        submissionStatusService = mock(OrderSubmissionStatusService.class);
        orderQueryService = mock(OrderQueryService.class);
        orderLifecycleService = mock(OrderLifecycleService.class);

        VoucherOrderController controller = new VoucherOrderController();
        ReflectionTestUtils.setField(controller, "voucherOrderService", voucherOrderService);
        ReflectionTestUtils.setField(controller, "orderSubmissionStatusService", submissionStatusService);
        ReflectionTestUtils.setField(controller, "orderQueryService", orderQueryService);
        ReflectionTestUtils.setField(controller, "orderLifecycleService", orderLifecycleService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void postSeckillContractUnchanged() throws Exception {
        when(voucherOrderService.seckillVoucher(10L)).thenReturn(Result.ok(999L));

        mockMvc.perform(post("/voucher-order/seckill/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(999));
    }

    @Test
    void cancelOrderReturnsOrderId() throws Exception {
        when(orderLifecycleService.cancelByCurrentUser(1001L)).thenReturn(1001L);

        mockMvc.perform(post("/voucher-order/1001/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(1001));

        verify(orderLifecycleService).cancelByCurrentUser(1001L);
    }

    @Test
    void cancelOrderRejectsNonPositiveOrderId() throws Exception {
        mockMvc.perform(post("/voucher-order/0/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("参数错误：orderId 必须大于 0"));

        mockMvc.perform(post("/voucher-order/-5/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("参数错误：orderId 必须大于 0"));

        verify(orderLifecycleService, never()).cancelByCurrentUser(anyLong());
    }

    @Test
    void cancelOrderNotFoundPassesThroughBusinessError() throws Exception {
        when(orderLifecycleService.cancelByCurrentUser(1001L))
                .thenThrow(new BusinessException("订单不存在"));

        mockMvc.perform(post("/voucher-order/1001/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("订单不存在"));
    }

    @Test
    void cancelOrderNotCancelablePassesThroughBusinessError() throws Exception {
        when(orderLifecycleService.cancelByCurrentUser(1001L))
                .thenThrow(new BusinessException("当前订单状态不可取消"));

        mockMvc.perform(post("/voucher-order/1001/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("当前订单状态不可取消"));
    }

    @Test
    void cancelPathReachesLifecycleOnlyAndStaticPostStillRoutesToSeckill() throws Exception {
        when(voucherOrderService.seckillVoucher(10L)).thenReturn(Result.ok(999L));
        when(orderLifecycleService.cancelByCurrentUser(7L)).thenReturn(7L);

        mockMvc.perform(post("/voucher-order/seckill/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(999));
        mockMvc.perform(post("/voucher-order/7/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(7));

        verify(voucherOrderService).seckillVoucher(10L);
        verify(orderLifecycleService).cancelByCurrentUser(7L);
        verify(orderLifecycleService, never()).cancelByCurrentUser(10L);
        verify(voucherOrderService, never()).seckillVoucher(7L);
        verify(submissionStatusService, never()).getSubmissionStatus(anyLong());
    }

    @Test
    void submissionStatusReturnsAccepted() throws Exception {
        when(submissionStatusService.getSubmissionStatus(1001L))
                .thenReturn(new OrderSubmissionStatusDTO(1001L, OrderSubmissionState.ACCEPTED,
                        "订单已受理，等待处理", 123L));

        mockMvc.perform(get("/voucher-order/submissions/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.state").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.message").value("订单已受理，等待处理"))
                .andExpect(jsonPath("$.data.updatedAt").value(123));
    }

    @Test
    void submissionStatusRejectsNonPositiveOrderId() throws Exception {
        mockMvc.perform(get("/voucher-order/submissions/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("参数错误：orderId 必须大于 0"));

        mockMvc.perform(get("/voucher-order/submissions/-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("参数错误：orderId 必须大于 0"));
    }

    @Test
    void getOrderReturnsDto() throws Exception {
        VoucherOrderDTO dto = new VoucherOrderDTO();
        dto.setId(1001L);
        dto.setVoucherId(2L);
        dto.setStatus(1);
        when(orderQueryService.getOrder(1001L)).thenReturn(dto);

        mockMvc.perform(get("/voucher-order/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1001))
                .andExpect(jsonPath("$.data.voucherId").value(2));
    }

    @Test
    void getOrderMissingReturnsNotExist() throws Exception {
        when(orderQueryService.getOrder(1001L)).thenReturn(null);

        mockMvc.perform(get("/voucher-order/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("订单不存在"));
    }

    @Test
    void mineUsesDefaultPagination() throws Exception {
        when(orderQueryService.pageMine(1, 10))
                .thenReturn(new VoucherOrderPageDTO(1, 10, 0L, List.of()));

        mockMvc.perform(get("/voucher-order/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(orderQueryService).pageMine(1, 10);
    }

    @Test
    void minePassesThroughValidParams() throws Exception {
        when(orderQueryService.pageMine(2, 25))
                .thenReturn(new VoucherOrderPageDTO(2, 25, 0L, List.of()));

        mockMvc.perform(get("/voucher-order/mine").param("current", "2").param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(orderQueryService).pageMine(2, 25);
    }

    @Test
    void mineRejectsInvalidParams() throws Exception {
        mockMvc.perform(get("/voucher-order/mine").param("current", "0"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("参数错误：current 必须大于等于 1"));

        mockMvc.perform(get("/voucher-order/mine").param("size", "0"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("参数错误：size 必须在 1 到 50 之间"));

        mockMvc.perform(get("/voucher-order/mine").param("size", "51"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("参数错误：size 必须在 1 到 50 之间"));
    }

    @Test
    void mineAcceptsSizeFifty() throws Exception {
        when(orderQueryService.pageMine(1, 50))
                .thenReturn(new VoucherOrderPageDTO(1, 50, 0L, List.of()));

        mockMvc.perform(get("/voucher-order/mine").param("size", "50"))
                .andExpect(jsonPath("$.success").value(true));

        verify(orderQueryService).pageMine(1, 50);
    }

    @Test
    void staticPathsAreNotSwallowedByOrderIdMapping() throws Exception {
        when(orderQueryService.pageMine(anyInt(), anyInt()))
                .thenReturn(new VoucherOrderPageDTO(1, 10, 0L, List.of()));

        mockMvc.perform(get("/voucher-order/mine"))
                .andExpect(status().isOk());
        verify(orderQueryService).pageMine(anyInt(), anyInt());
        verify(orderQueryService, never()).getOrder(anyLong());
        verify(submissionStatusService, never()).getSubmissionStatus(anyLong());
    }

    @Test
    void submissionsPathIsNotSwallowedByOrderIdMapping() throws Exception {
        when(submissionStatusService.getSubmissionStatus(7L))
                .thenReturn(new OrderSubmissionStatusDTO(7L, OrderSubmissionState.PROCESSING, "订单处理中", 1L));

        mockMvc.perform(get("/voucher-order/submissions/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROCESSING"));

        verify(submissionStatusService).getSubmissionStatus(7L);
        verify(orderQueryService, never()).getOrder(anyLong());
    }

    @Test
    void orderIdPathReachesGetOrderOnly() throws Exception {
        when(orderQueryService.getOrder(7L)).thenReturn(null);

        mockMvc.perform(get("/voucher-order/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMsg").value("订单不存在"));

        verify(orderQueryService).getOrder(7L);
        verify(submissionStatusService, never()).getSubmissionStatus(anyLong());
    }
}
