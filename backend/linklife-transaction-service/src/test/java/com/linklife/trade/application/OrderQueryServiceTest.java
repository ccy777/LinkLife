package com.linklife.trade.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linklife.common.core.context.UserContext;
import com.linklife.trade.dto.VoucherOrderDTO;
import com.linklife.trade.dto.VoucherOrderPageDTO;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderQueryService 单元测试：单个订单按 id+userId 查询、用户隔离、默认分页与参数透传、
 * 排序固定 create_time DESC / id DESC、DTO 不暴露 userId。不连接真实 MySQL。
 */
class OrderQueryServiceTest {

    private OrderQueryService service;
    private VoucherOrderMapper mapper;

    @BeforeEach
    void setUp() {
        // 纯单元测试无 MyBatis-Plus 上下文：初始化实体元数据，使 LambdaQueryWrapper.getSqlSegment() 可用
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), VoucherOrder.class);
        service = new OrderQueryService();
        mapper = mock(VoucherOrderMapper.class);
        ReflectionTestUtils.setField(service, "voucherOrderMapper", mapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void asUser(long userId) {
        UserContext.set(userId);
    }

    private VoucherOrder order(long id) {
        VoucherOrder order = new VoucherOrder();
        order.setId(id);
        order.setUserId(1L);
        order.setVoucherId(2L);
        order.setStatus(1);
        return order;
    }

    @Test
    void getOrderQueriesByIdAndCurrentUserIdInOneCondition() {
        asUser(1L);
        when(mapper.selectOne(any())).thenReturn(order(1001L));

        VoucherOrderDTO dto = service.getOrder(1001L);

        assertThat(dto.getId()).isEqualTo(1001L);
        ArgumentCaptor<LambdaQueryWrapper<VoucherOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(captor.capture());
        String segment = captor.getValue().getSqlSegment();
        assertThat(segment).contains("id").contains("user_id");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(1001L, 1L);
    }

    @Test
    void getOrderNotFoundReturnsNull() {
        asUser(1L);
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(service.getOrder(1001L)).isNull();
    }

    @Test
    void otherUsersOrderIsNeverReturned() {
        asUser(2L);
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(service.getOrder(1001L)).isNull();
    }

    @Test
    void pageMineDefaultsToCurrentOneSizeTenWithStableOrdering() {
        asUser(1L);
        Page<VoucherOrder> page = new Page<>(1, 10, 2);
        page.setRecords(List.of(order(1L), order(2L)));
        when(mapper.selectPage(any(Page.class), any())).thenReturn(page);

        VoucherOrderPageDTO result = service.pageMine(1, 10);

        assertThat(result.getCurrent()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(10);
        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).hasSize(2);

        ArgumentCaptor<Page<VoucherOrder>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<LambdaQueryWrapper<VoucherOrder>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
        String segment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(segment).contains("create_time DESC");
        assertThat(segment).contains("id DESC");
        assertThat(segment.indexOf("create_time DESC")).isLessThan(segment.indexOf("id DESC"));
    }

    @Test
    void pageMinePassesThroughCurrentAndSize() {
        asUser(1L);
        Page<VoucherOrder> page = new Page<>(3, 25, 0);
        when(mapper.selectPage(any(Page.class), any())).thenReturn(page);

        VoucherOrderPageDTO result = service.pageMine(3, 25);

        ArgumentCaptor<Page<VoucherOrder>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(3);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(25);
        assertThat(result.getCurrent()).isEqualTo(3);
        assertThat(result.getSize()).isEqualTo(25);
    }

    @Test
    void dtoNeverExposesUserId() {
        assertThat(Arrays.stream(VoucherOrderDTO.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("userId");
        assertThat(Arrays.stream(VoucherOrderDTO.class.getMethods())
                .map(Method::getName))
                .doesNotContain("getUserId");
    }
}
