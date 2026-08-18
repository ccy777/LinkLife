package com.linklife.promotion.controller;

import com.linklife.promotion.entity.Voucher;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.common.web.exception.GlobalExceptionHandler;
import com.linklife.promotion.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 优惠券保存返回值处理测试：standalone MockMvc，不连接数据库。
 */
class VoucherControllerTest {

    private MockMvc mockMvc;
    private IVoucherService voucherService;

    @BeforeEach
    void setUp() {
        voucherService = mock(IVoucherService.class);
        VoucherController controller = new VoucherController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "voucherService", voucherService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void addVoucherSaveTrueReturnsId() throws Exception {
        // mock save 必须显式回填数据库生成的 ID，断言成功响应 data 真的包含该 ID
        doAnswer(invocation -> {
            invocation.getArgument(0, Voucher.class).setId(42L);
            return true;
        }).when(voucherService).save(any(Voucher.class));

        mockMvc.perform(post("/voucher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(42));
    }

    @Test
    void addVoucherSaveTrueWithoutGeneratedIdReturnsFailure() throws Exception {
        // save 返回 true 但未生成 ID：不得返回 success
        when(voucherService.save(any(Voucher.class))).thenReturn(true);

        mockMvc.perform(post("/voucher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"demo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("优惠券保存失败"));
    }

    @Test
    void addVoucherSaveFalseReturnsFailure() throws Exception {
        when(voucherService.save(any(Voucher.class))).thenReturn(false);

        mockMvc.perform(post("/voucher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"demo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("优惠券保存失败"));
    }

    @Test
    void addVoucherServiceExceptionReturns500() throws Exception {
        when(voucherService.save(any(Voucher.class)))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/voucher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"demo\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void addSeckillVoucherFailureDoesNotReturnSuccess() throws Exception {
        doThrow(new BusinessException("秒杀券保存失败，事务回滚"))
                .when(voucherService).addSeckillVoucher(any(Voucher.class));

        mockMvc.perform(post("/voucher/seckill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"seckill\",\"stock\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("秒杀券保存失败，事务回滚"));
    }

    @Test
    void addSeckillVoucherSuccessReturnsGeneratedId() throws Exception {
        // mock 服务保存成功后必须已回填 ID，控制器 data 必须为该 ID
        doAnswer(invocation -> {
            invocation.getArgument(0, Voucher.class).setId(88L);
            return null;
        }).when(voucherService).addSeckillVoucher(any(Voucher.class));

        mockMvc.perform(post("/voucher/seckill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"seckill\",\"stock\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(88));
    }
}
