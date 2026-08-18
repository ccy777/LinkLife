-- 002_social_consistency.sql（一次性迁移，不设计为可无限重复执行）
-- 017J-D：社交关系、博客点赞与关注流一致性收口
-- 用途：清理自关注与重复关注、添加 follow 唯一约束/反向索引、
--       创建 tb_blog_like、添加关注流查询所需博客索引。
-- 注意：本脚本只应在既有库上执行一次；重新执行可能因索引/表已存在而报错，
--       不伪装成可重复运行的 migration。

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. 删除历史自关注（user_id = follow_user_id）
DELETE FROM `tb_follow` WHERE `user_id` = `follow_user_id`;

-- 2. 对重复 (user_id, follow_user_id) 只保留最小 id
DELETE f1 FROM `tb_follow` f1
JOIN `tb_follow` f2
  ON f1.`user_id` = f2.`user_id`
 AND f1.`follow_user_id` = f2.`follow_user_id`
 AND f1.`id` > f2.`id`;

-- 3. 添加唯一约束与反向索引（重复清理后才可成功）
ALTER TABLE `tb_follow`
  ADD UNIQUE KEY `uk_follow_user_target` (`user_id`, `follow_user_id`),
  ADD KEY `idx_follow_target_user` (`follow_user_id`, `user_id`);

-- 4. 创建博客点赞明细表（MySQL 为点赞事实来源）
CREATE TABLE `tb_blog_like`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `blog_id` bigint(20) UNSIGNED NOT NULL COMMENT '博客id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '点赞用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_blog_like` (`blog_id`, `user_id`) USING BTREE,
  KEY `idx_blog_like_blog_time` (`blog_id`, `create_time`, `id`) USING BTREE,
  KEY `idx_blog_like_user_time` (`user_id`, `create_time`, `id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact COMMENT = '博客点赞明细表';

-- 5. 迁移边界重置旧聚合计数（017J-D-R1）
-- 旧版本点赞用户身份只存在于 Redis ZSet；本 migration 不扫描旧 Redis；
-- 无法仅凭聚合 liked 恢复用户级明细；因此迁移边界将旧 aggregate 重置为 0，
-- 之后由 tb_blog_like 事务维护。
UPDATE `tb_blog`
SET `liked` = 0
WHERE `liked` IS NULL OR `liked` <> 0;

-- 6. 添加关注流查询所需博客索引（user_id, create_time, id）
ALTER TABLE `tb_blog`
  ADD KEY `idx_blog_user_create_id` (`user_id`, `create_time`, `id`);

SET FOREIGN_KEY_CHECKS = 1;
