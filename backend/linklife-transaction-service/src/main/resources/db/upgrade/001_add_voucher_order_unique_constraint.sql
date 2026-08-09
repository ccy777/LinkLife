-- =====================================================================
-- 001: tb_voucher_order 增加"一人一券"唯一约束
-- 目标表: tb_voucher_order
-- 约束  : uk_user_voucher (user_id, voucher_id)
-- 说明  : 本脚本只用于已存在旧表结构的升级场景。
--         全新初始化请直接使用 current schema（已包含该唯一索引）。
--         执行前必须先处理重复数据，禁止在存在重复数据时直接建索引。
-- =====================================================================

-- 1. 重复数据检查 SQL
--    执行结果 COUNT > 0 或返回任意行时，说明当前表已存在同一用户对同一优惠券
--    的多张订单，必须先人工处理（保留一张、合并或作废其余）后再执行建索引 SQL。
--    注意：此时不得直接执行第 2 步的 ALTER TABLE。
SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;

-- 2. 增加唯一索引 SQL
--    仅在步骤 1 返回空结果（无重复数据）时执行。
ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`);

-- 3. 索引存在性检查（可选）
--    返回 1 表示索引已存在。
SELECT COUNT(*) AS index_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'tb_voucher_order'
  AND index_name = 'uk_user_voucher';

-- 说明：本仓库中的 该迁移不会由应用启动过程自动执行；
--       以上 SQL 需要由具备权限的 DBA/开发者在确认数据后手动执行。
