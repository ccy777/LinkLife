package com.linklife.trade.lifecycle.integration;

import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.promotion.entity.Voucher;
import com.linklife.promotion.service.impl.VoucherServiceImpl;
import com.linklife.trade.admission.RedisSeckillAdmissionAdapter;
import com.linklife.trade.admission.SeckillAdmissionDecision;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 017J-B 手工集成（Outbox 默认关闭）：创建请求只提交 MySQL 与 PENDING Outbox，
 * 不写 Redis；秒杀准入返回 NOT_INITIALIZED（code 3）为安全短暂状态。
 */
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_INTEGRATION_ENABLED", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_CONFIRM_ISOLATED", matches = "(?i)true")
@ManualIntegrationEnvironment.FullIsolationRequired
  @SpringBootTest(classes = com.linklife.transaction.TransactionServiceApplication.class)
class SeckillVoucherOutboxDisabledManualIntegration extends Stage3E017gIntegrationSupport {

    @Resource
    private VoucherServiceImpl voucherService;

    @Resource
    private RedisSeckillAdmissionAdapter admissionAdapter;

    private final List<Long> createdVoucherIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long voucherId : createdVoucherIds) {
            jdbcTemplate.update("DELETE FROM tb_outbox_event WHERE aggregate_id = ?", voucherId);
            jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
            jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", voucherId);
            stringRedisTemplate.delete(stockKey(voucherId));
            stringRedisTemplate.delete("transaction:seckill:begin:" + voucherId);
            stringRedisTemplate.delete("transaction:seckill:end:" + voucherId);
            stringRedisTemplate.delete("transaction:seckill:init:marker:" + voucherId);
        }
        createdVoucherIds.clear();
    }

    @Test
    void createCommitsMySqlAndPendingOutboxButNotRedisWhenOutboxDisabled() {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("集成测试秒杀券-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        voucher.setSubTitle("sub");
        voucher.setRules("rules");
        voucher.setPayValue(100L);
        voucher.setActualValue(100L);
        voucher.setType(1);
        voucher.setStatus(1);
        voucher.setStock(10);
        voucher.setBeginTime(LocalDateTime.now().plusHours(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        voucher.setEndTime(LocalDateTime.now().plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));

        voucherService.addSeckillVoucher(voucher);
        assertThat(voucher.getId()).isNotNull();
        createdVoucherIds.add(voucher.getId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher WHERE id = ?", Integer.class, voucher.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tb_outbox_event WHERE business_key = ?",
                String.class, "SECKILL_VOUCHER:CREATED:" + voucher.getId() + ":V1"))
                .isEqualTo("PENDING");
        assertThat(stringRedisTemplate.hasKey(stockKey(voucher.getId()))).isFalse();
        assertThat(stringRedisTemplate.hasKey("transaction:seckill:init:marker:" + voucher.getId())).isFalse();
    }

    @Test
    void admissionReturnsNotInitializedBeforeOutboxProcessing() {
        long voucherId = 9_800_000_000L;
        createdVoucherIds.add(voucherId);
        // Redis 尚未初始化：stock/begin/end 均不存在
        SeckillAdmissionDecision decision = admissionAdapter.admit(
                voucherId, 9001L, 9_900_000_000L, System.currentTimeMillis(), 86_400L);

        assertThat(decision).isEqualTo(SeckillAdmissionDecision.NOT_INITIALIZED);
    }
}
