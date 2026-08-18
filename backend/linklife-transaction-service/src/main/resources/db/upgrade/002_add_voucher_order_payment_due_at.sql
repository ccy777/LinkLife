-- 002: 将订单级支付到期时刻固化为业务事实，供 RocketMQ 与 Scheduler 共用。
-- 历史正式默认为 15 分钟，因此默认回填 create_time + 15 MINUTE。
-- 若某环境在本迁移前长期使用自定义 payment-timeout，执行 UPDATE 时必须改用该历史值，
-- 不得用当前或未来配置重新解释已存在订单。
-- 正式 payment-timeout 契约为 [1s,24h] 的整秒 Duration；普通 TIMESTAMP 按秒保存事实。

ALTER TABLE `tb_voucher_order`
  ADD COLUMN `payment_due_at` timestamp NULL AFTER `create_time`;

UPDATE `tb_voucher_order`
SET `payment_due_at` = DATE_ADD(`create_time`, INTERVAL 15 MINUTE)
WHERE `payment_due_at` IS NULL;

ALTER TABLE `tb_voucher_order`
  MODIFY COLUMN `payment_due_at` timestamp NOT NULL COMMENT '订单创建时冻结的支付到期绝对时刻（秒级精度）',
  DROP INDEX `idx_status_create_time_id`,
  ADD KEY `idx_status_payment_due_at_id` (`status`, `payment_due_at`, `id`) USING BTREE;
