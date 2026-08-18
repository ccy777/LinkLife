-- Transaction schema（legacy monolith schema 拆分：5 张表）

DROP TABLE IF EXISTS `tb_voucher`;
CREATE TABLE `tb_voucher`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '商铺id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '代金券标题',
  `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '副标题',
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '使用规则',
  `pay_value` bigint(10) UNSIGNED NOT NULL COMMENT '支付金额，单位是分。例如200代表2元',
  `actual_value` bigint(10) NOT NULL COMMENT '抵扣金额，单位是分。例如200代表2元',
  `type` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0,普通券；1,秒杀券',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1,上架; 2,下架; 3,过期',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_voucher
-- ----------------------------
INSERT INTO `tb_voucher` VALUES (1, 1, '周末双人代金券', '周末时段全场通用', '全场通用\n无需预约\n可无限叠加\n不兑现、不找零\n仅限堂食', 4750, 5000, 0, 1, '2022-01-04 09:42:39', '2022-01-04 09:43:31');

-- ----------------------------
-- Table structure for tb_voucher_order
-- ----------------------------

DROP TABLE IF EXISTS `tb_seckill_voucher`;
CREATE TABLE `tb_seckill_voucher`  (
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的优惠券的id',
  `stock` int(8) NOT NULL COMMENT '库存',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `begin_time` timestamp NOT NULL COMMENT '生效时间',
  `end_time` timestamp NOT NULL COMMENT '失效时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`voucher_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '秒杀优惠券表，与优惠券是一对一关系' ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_seckill_voucher
-- ----------------------------

-- ----------------------------
-- Table structure for tb_shop
-- ----------------------------

DROP TABLE IF EXISTS `tb_voucher_order`;
CREATE TABLE `tb_voucher_order`  (
  `id` bigint(20) NOT NULL COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单的用户id',
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT '购买的代金券id',
  `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '支付方式 1：余额支付；2：支付宝；3：微信',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `payment_due_at` timestamp NOT NULL COMMENT '订单创建时冻结的支付到期绝对时刻（秒级精度）',
  `pay_time` timestamp NULL DEFAULT NULL COMMENT '支付时间',
  `use_time` timestamp NULL DEFAULT NULL COMMENT '核销时间',
  `refund_time` timestamp NULL DEFAULT NULL COMMENT '退款时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`) USING BTREE,
  KEY `idx_status_payment_due_at_id` (`status`, `payment_due_at`, `id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_voucher_order
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;

-- ----------------------------
-- Table structure for tb_order_status_log
-- ----------------------------

DROP TABLE IF EXISTS `tb_order_status_log`;
CREATE TABLE `tb_order_status_log`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` bigint(20) NOT NULL COMMENT '订单id',
  `from_status` tinyint(1) UNSIGNED NOT NULL COMMENT '迁移前状态，1：未支付',
  `to_status` tinyint(1) UNSIGNED NOT NULL COMMENT '迁移后状态，4：已取消',
  `trigger_type` varchar(32) NOT NULL COMMENT '触发来源：USER_CANCEL/TIMEOUT_CLOSE（审计属性）',
  `operator_type` varchar(16) NOT NULL COMMENT '操作人类型：USER/SYSTEM',
  `operator_id` bigint(20) NULL DEFAULT NULL COMMENT '操作人id（系统触发为空）',
  `reason_code` varchar(32) NOT NULL COMMENT '稳定原因码',
  `reason_detail` varchar(200) NULL DEFAULT NULL COMMENT '限长稳定文案，禁止敏感信息',
  `idempotency_key` varchar(64) NOT NULL COMMENT '业务幂等键：ORDER_STATUS:{orderId}:{from}:{to}',
  `created_time` datetime NOT NULL COMMENT '创建时间（关闭命令now）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_order_status_log_idem` (`idempotency_key`) USING BTREE,
  UNIQUE KEY `uk_order_status_log_transition` (`order_id`, `from_status`, `to_status`) USING BTREE,
  KEY `idx_order_status_log_order` (`order_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '订单状态迁移审计日志' ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_order_status_log
-- ----------------------------

-- ----------------------------
-- Table structure for tb_outbox_event
-- ----------------------------

DROP TABLE IF EXISTS `tb_outbox_event`;
CREATE TABLE `tb_outbox_event`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id` varchar(64) NOT NULL COMMENT '全局唯一事件id（应用内UUID）',
  `business_key` varchar(96) NOT NULL COMMENT '确定性业务唯一键：VOUCHER_ORDER:CLOSED:{orderId}:V1',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型：VOUCHER_ORDER',
  `aggregate_id` bigint(20) NOT NULL COMMENT '聚合id：orderId',
  `event_type` varchar(32) NOT NULL COMMENT '事件类型：ORDER_CLOSED',
  `event_version` int(11) NOT NULL COMMENT '事件版本：1',
  `payload` varchar(1024) NOT NULL COMMENT 'JSON payload（最小必要字段，无敏感信息）',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCESS/DEAD',
  `retry_count` int(11) NOT NULL DEFAULT 0 COMMENT '重试次数',
  `next_retry_time` datetime NOT NULL COMMENT '下次可处理时间',
  `lock_token` varchar(64) NULL DEFAULT NULL COMMENT '领取令牌（可空，仅PROCESSING要求有值）',
  `locked_until` datetime NULL DEFAULT NULL COMMENT '租约到期时间（可空）',
  `processing_started_time` datetime NULL DEFAULT NULL COMMENT '领取开始时间（可空）',
  `last_error_code` varchar(64) NULL DEFAULT NULL COMMENT '最近错误码（可空）',
  `created_time` datetime NOT NULL COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `completed_time` datetime NULL DEFAULT NULL COMMENT '完成时间（可空）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_outbox_event_id` (`event_id`) USING BTREE,
  UNIQUE KEY `uk_outbox_business_key` (`business_key`) USING BTREE,
  KEY `idx_outbox_status_next_retry` (`status`, `next_retry_time`) USING BTREE,
  KEY `idx_outbox_status_locked_until` (`status`, `locked_until`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '本地Outbox事件表' ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_outbox_event
-- ----------------------------
