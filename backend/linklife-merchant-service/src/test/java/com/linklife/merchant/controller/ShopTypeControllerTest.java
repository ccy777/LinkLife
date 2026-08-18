package com.linklife.merchant.controller;

import com.linklife.merchant.entity.ShopType;
import com.linklife.merchant.service.IShopTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ShopTypeController（Stage5B）：/shop-type/list 走缓存方法。
 */
class ShopTypeControllerTest {

    private MockMvc mockMvc;
    private IShopTypeService typeService;

    @BeforeEach
    void setUp() {
        typeService = mock(IShopTypeService.class);
        ShopTypeController controller = new ShopTypeController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "typeService", typeService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listUsesCachedMethod() throws Exception {
        ShopType t = new ShopType();
        t.setId(1L);
        t.setName("美食");
        t.setSort(1);
        when(typeService.queryTypeListCached()).thenReturn(List.of(t));

        mockMvc.perform(get("/shop-type/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("美食"));

        verify(typeService).queryTypeListCached();
    }
}
