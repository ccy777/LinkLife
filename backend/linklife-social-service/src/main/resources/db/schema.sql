-- Social schema（legacy monolith schema 拆分：4 张表 + seed 数据 + liked 重置 + tb_blog_like）

DROP TABLE IF EXISTS `tb_blog`;
CREATE TABLE `tb_blog`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) NOT NULL COMMENT '商户id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `images` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '探店的照片，最多9张，多张以\",\"隔开',
  `content` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '探店的文字描述',
  `liked` int(8) UNSIGNED NULL DEFAULT 00000000 COMMENT '点赞数量',
  `comments` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '评论数量',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_blog_user_create_id` (`user_id`, `create_time`, `id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_blog
-- ----------------------------
INSERT INTO `tb_blog` VALUES (4, 4, 2, '周末散步到南桥小馆，点了一份安静的午餐', '', '沿着河边慢慢走，正好到南桥小馆。午市人不多，点了一份套餐和一杯热茶，窗边的位置能看到街角。适合周末一个人放空，也适合和朋友慢慢聊天。', 0, 00000104, '2021-12-28 19:50:01', '2022-01-06 20:30:03');
INSERT INTO `tb_blog` VALUES (5, 1, 2, '拾光咖啡的清晨与手冲', '', '早上九点的拾光咖啡刚开门，吧台正在准备第一壶手冲。点了一杯埃塞俄比亚的豆子，酸质明亮，配一块可颂刚刚好。如果路过城北，值得进来坐一会儿。', 0, 00000000, '2021-12-28 20:57:49', '2022-01-06 20:30:22');
INSERT INTO `tb_blog` VALUES (6, 10, 1, '夜晚的回声音乐空间，适合放松', '', '工作日晚上和同事去了回声音乐空间，点了两杯无酒精饮品。灯光很柔和，音乐声量刚好，不会太吵。适合下班后聊天，也适合一个人听歌发呆。', 0, 00000000, '2022-01-11 16:05:47', '2022-01-11 16:05:47');
INSERT INTO `tb_blog` VALUES (7, 10, 1, '回声音乐空间的周末欢唱时光', '', '周末和朋友在回声音乐空间订了一间小包厢，点了几首歌和两杯饮品。隔音很好，音响效果舒服，唱到尽兴也没有打扰别人。散场时前台还送了两张下次使用的饮品券。', 0, 00000000, '2022-01-11 16:05:47', '2022-01-11 16:05:47');

-- Stage 3 以 tb_blog_like 为点赞事实源；全新 seed 不保留无法对应用户明细的历史聚合点赞数。
UPDATE `tb_blog` SET `liked` = 0;

-- ----------------------------
-- Table structure for tb_blog_like
-- ----------------------------

DROP TABLE IF EXISTS `tb_blog_like`;
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

-- ----------------------------
-- Table structure for tb_blog_comments
-- ----------------------------

DROP TABLE IF EXISTS `tb_blog_comments`;
CREATE TABLE `tb_blog_comments`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `blog_id` bigint(20) UNSIGNED NOT NULL COMMENT '探店id',
  `parent_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的1级评论id，如果是一级评论，则值为0',
  `answer_id` bigint(20) UNSIGNED NOT NULL COMMENT '回复的评论id',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '回复的内容',
  `liked` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '点赞数',
  `status` tinyint(1) UNSIGNED NULL DEFAULT NULL COMMENT '状态，0：正常，1：被举报，2：禁止查看',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_blog_comments
-- ----------------------------

-- ----------------------------
-- Table structure for tb_follow
-- ----------------------------

DROP TABLE IF EXISTS `tb_follow`;
CREATE TABLE `tb_follow`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `follow_user_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_follow_user_target` (`user_id`, `follow_user_id`) USING BTREE,
  KEY `idx_follow_target_user` (`follow_user_id`, `user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_follow
-- ----------------------------

-- ----------------------------
-- Table structure for tb_seckill_voucher
-- ----------------------------
