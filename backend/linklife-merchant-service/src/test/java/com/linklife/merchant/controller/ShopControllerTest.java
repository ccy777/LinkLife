package com.linklife.merchant.controller;

import com.linklife.merchant.entity.Shop;
import com.linklife.common.web.exception.GlobalExceptionHandler;
import com.linklife.common.core.api.Result;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.merchant.service.IShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商铺保存返回值处理测试：standalone MockMvc，不连接数据库。
 */
class ShopControllerTest {

    private MockMvc mockMvc;
    private IShopService shopService;

    @BeforeEach
    void setUp() {
        shopService = mock(IShopService.class);
        ShopController controller = new ShopController();
        controller.shopService = shopService;
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void saveTrueReturnsId() throws Exception {
        when(shopService.createShop(any(Shop.class))).thenReturn(Result.ok(7L));

        mockMvc.perform(post("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(7));
    }

    @Test
    void saveTrueWithoutGeneratedIdReturnsFailure() throws Exception {
        when(shopService.createShop(any(Shop.class)))
                .thenThrow(new BusinessException("店铺保存失败"));

        mockMvc.perform(post("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("店铺保存失败"));
    }

    @Test
    void saveFalseReturnsFailure() throws Exception {
        when(shopService.createShop(any(Shop.class)))
                .thenThrow(new BusinessException("店铺保存失败"));

        mockMvc.perform(post("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("店铺保存失败"));
    }

    @Test
    void serviceExceptionReturns500() throws Exception {
        when(shopService.createShop(any(Shop.class)))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void missingBodyReturnsStable400() throws Exception {
        mockMvc.perform(post("/shop").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMsg").value("请求参数错误"));
    }
}
