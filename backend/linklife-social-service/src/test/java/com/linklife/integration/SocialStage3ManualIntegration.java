package com.linklife.integration;

import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.user.UserSummaryDTO;
import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.common.core.api.Result;
import com.linklife.social.client.IdentityUserClient;
import com.linklife.social.dto.ScrollResult;
import com.linklife.social.entity.Blog;
import com.linklife.social.service.IBlogService;
import com.linklife.social.service.IFollowService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 017J-D 社交一致性真实隔离集成：仅在本任务专用 MySQL 8.4.4 + Redis 7 环境手动执行。
 *
 * <p>类级前置条件 {@link ManualIntegrationEnvironment.FullIsolationRequired}
 * 在 Spring 上下文创建前校验：DB URL 必须指向含 test/stage1 的测试 schema、
 * Redis database 必须大于 0，禁止连接正式库。环境变量通过
 * {@link DynamicPropertySource} 显式映射，不打印任何连接值。</p>
 *
 * <p>不进入默认 Surefire 套件；显式执行：
 * {@code mvn -o -Dtest=SocialStage3ManualIntegration test}。
 * 每个测试使用高位专用 id，finally 中精确清理本测试创建的 MySQL 行与 Redis Key。</p>
 */
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_INTEGRATION_ENABLED", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_CONFIRM_ISOLATED", matches = "(?i)true")
@ManualIntegrationEnvironment.FullIsolationRequired
@SpringBootTest(classes = com.linklife.social.SocialServiceApplication.class)
class SocialStage3ManualIntegration {

    private static final long USER_BASE = 9_100_000_000L;
    private static final long BLOG_BASE = 7_100_000_000L;
    private static final AtomicLong USER_SEQ = new AtomicLong(1L);
    private static final AtomicLong BLOG_SEQ = new AtomicLong(1L);
    private static final Map<Long, UserSummaryDTO> TEST_USERS = new HashMap<>();

    @MockitoBean
    private IdentityUserClient identityUserClient;

    @Resource
    private IFollowService followService;

    @Resource
    private IBlogService blogService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @DynamicPropertySource
    static void manualEnvironmentProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", ManualIntegrationEnvironment::redisHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(ManualIntegrationEnvironment.redisPort()));
        registry.add("spring.data.redis.database", () -> String.valueOf(ManualIntegrationEnvironment.redisDatabase()));
        String redisPassword = ManualIntegrationEnvironment.redisPassword();
        if (redisPassword != null) {
            registry.add("spring.data.redis.password", () -> redisPassword);
        }
        registry.add("spring.datasource.url", ManualIntegrationEnvironment::dbUrl);
        registry.add("spring.datasource.username", ManualIntegrationEnvironment::dbUsername);
        String dbPassword = ManualIntegrationEnvironment.dbPassword();
        if (dbPassword != null) {
            registry.add("spring.datasource.password", () -> dbPassword);
        }
    }

    @AfterEach
    void clearUserHolder() {
        UserContext.clear();
    }

    /**
     * 每个用例开始前清理本任务保留区间（高位 id）可能存在的运行残留，
     * 使套件可重复执行；只删除本任务创建的专用数据，不触碰种子与正式数据。
     */
    @BeforeEach
    void cleanResidue() {
        jdbcTemplate.update("DELETE FROM tb_blog_like WHERE blog_id >= ?", BLOG_BASE);
        jdbcTemplate.update("DELETE FROM tb_blog WHERE id >= ?", BLOG_BASE);
        jdbcTemplate.update("DELETE FROM tb_follow WHERE user_id >= ? OR follow_user_id >= ?",
                USER_BASE, USER_BASE);
        TEST_USERS.clear();
        for (String pattern : new String[]{"feed:91*", "follows:91*", "blog:liked:71*"}) {
            Set<String> keys = stringRedisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        }
        when(identityUserClient.batch(any())).thenAnswer(invocation -> {
            com.linklife.common.core.user.UserSummaryRequest request =
                    invocation.getArgument(0);
            if (request == null || request.isEmpty()) {
                return List.of();
            }
            List<UserSummaryDTO> result = new java.util.ArrayList<>();
            for (Long id : request.userIds()) {
                UserSummaryDTO dto = TEST_USERS.get(id);
                if (dto != null) {
                    result.add(dto);
                }
            }
            return result;
        });
    }

    // ---------------- helpers ----------------

    private long nextUserId() {
        return USER_BASE + USER_SEQ.getAndIncrement();
    }

    private long nextBlogId() {
        return BLOG_BASE + BLOG_SEQ.getAndIncrement();
    }

    private void login(long userId) {
        UserContext.set(userId);
    }

    private void insertUser(long id) {
        TEST_USERS.put(id, new UserSummaryDTO(id, "user-" + id, "icon-" + id));
    }

    private void insertBlog(long id, long userId, LocalDateTime createTime) {
        jdbcTemplate.update("INSERT INTO tb_blog "
                        + "(id, shop_id, user_id, title, images, content, liked, comments, create_time, update_time) "
                        + "VALUES (?, 1, ?, ?, '/imgs/017jd.jpg', ?, 0, 0, ?, NOW())",
                id, userId, "blog-" + id, "content-" + id, createTime);
    }

    private void insertBlogLike(long blogId, long userId, LocalDateTime createTime) {
        jdbcTemplate.update("INSERT INTO tb_blog_like (blog_id, user_id, create_time) VALUES (?, ?, ?)",
                blogId, userId, createTime);
    }

    private int followCount(long userId, long targetId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_follow WHERE user_id = ? AND follow_user_id = ?",
                Integer.class, userId, targetId);
        return c == null ? 0 : c;
    }

    private int likeDetailCount(long blogId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ?", Integer.class, blogId);
        return c == null ? 0 : c;
    }

    private int blogLiked(long blogId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT liked FROM tb_blog WHERE id = ?", Integer.class, blogId);
        return c == null ? -1 : c;
    }

    private void deleteFollow(long userId, long targetId) {
        jdbcTemplate.update("DELETE FROM tb_follow WHERE user_id = ? AND follow_user_id = ?",
                userId, targetId);
    }

    private void deleteFollowRows(long... userIds) {
        for (long id : userIds) {
            jdbcTemplate.update("DELETE FROM tb_follow WHERE user_id = ? OR follow_user_id = ?", id, id);
        }
    }

    private void deleteUsers(long... userIds) {
        for (long id : userIds) {
            TEST_USERS.remove(id);
        }
    }

    private void deleteBlogAndLikes(long blogId) {
        jdbcTemplate.update("DELETE FROM tb_blog_like WHERE blog_id = ?", blogId);
        jdbcTemplate.update("DELETE FROM tb_blog WHERE id = ?", blogId);
    }

    private void deleteRedisKeys(String... keys) {
        for (String key : keys) {
            stringRedisTemplate.delete(key);
        }
    }

    private long nowMillis() {
        return System.currentTimeMillis();
    }

    private long millis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private List<Long> feedIds(Result result) {
        ScrollResult scroll = (ScrollResult) result.getData();
        List<?> list = scroll.getList() == null ? Collections.emptyList() : scroll.getList();
        return list.stream().map(b -> ((Blog) b).getId()).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<Long> userIds(Result result) {
        List<UserSummaryDTO> users = (List<UserSummaryDTO>) result.getData();
        return users.stream().map(UserSummaryDTO::id).collect(Collectors.toList());
    }

    // ---------------- scenarios ----------------

    @Test
    void freshSeedBaselineHasZeroLikedAndEmptyBlogLike() {
        Integer likeCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_blog_like", Integer.class);
        assertThat(likeCount).isEqualTo(0);
        Integer badLiked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog WHERE liked IS NULL OR liked <> 0", Integer.class);
        assertThat(badLiked).isEqualTo(0);
    }

    @Test
    void upgradeMigrationResetsLegacyLikedOnOldSchema() throws Exception {
        String alt = "linklife_it_017jd_r1_stage1_test";
        try {
            jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS `" + alt + "`");
            // 旧版 tb_follow：无唯一约束，含自关注与重复关注
            jdbcTemplate.execute("CREATE TABLE `" + alt + "`.`tb_follow` ("
                    + "`id` bigint(20) NOT NULL AUTO_INCREMENT,"
                    + "`user_id` bigint(20) UNSIGNED NOT NULL,"
                    + "`follow_user_id` bigint(20) UNSIGNED NOT NULL,"
                    + "`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // 旧版 tb_blog：无 idx_blog_user_create_id，liked 含历史聚合值
            jdbcTemplate.execute("CREATE TABLE `" + alt + "`.`tb_blog` ("
                    + "`id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,"
                    + "`shop_id` bigint(20) NOT NULL,"
                    + "`user_id` bigint(20) UNSIGNED NOT NULL,"
                    + "`title` varchar(255) NOT NULL,"
                    + "`images` varchar(2048) NOT NULL,"
                    + "`content` varchar(2048) NOT NULL,"
                    + "`liked` int(8) UNSIGNED NULL DEFAULT 0,"
                    + "`comments` int(8) UNSIGNED NULL DEFAULT NULL,"
                    + "`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "`update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP "
                    + "ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.update("INSERT INTO `" + alt + "`.`tb_follow` "
                            + "(id, user_id, follow_user_id, create_time) "
                            + "VALUES (1, 101, 101, NOW()), (2, 102, 103, NOW()), (3, 102, 103, NOW())");
            jdbcTemplate.update("INSERT INTO `" + alt + "`.`tb_blog` "
                            + "(id, shop_id, user_id, title, images, content, liked, comments, "
                            + "create_time, update_time) "
                            + "VALUES (1, 1, 2, 'legacy-1', '/i', 'c1', 11, 104, NOW(), NOW()), "
                            + "(2, 1, 2, 'legacy-2', '/i', 'c2', 5, 7, NOW(), NOW())");

            // 对旧 schema 应用正式 migration：使用独立 DriverManager 连接（不经过共享连接池，
            // 避免 USE 第二库污染池连接导致后续用例出现 “No database selected”）
            Properties props = new Properties();
            props.setProperty("user", ManualIntegrationEnvironment.dbUsername());
            if (ManualIntegrationEnvironment.dbPassword() != null) {
                props.setProperty("password", ManualIntegrationEnvironment.dbPassword());
            }
            try (Connection conn = DriverManager.getConnection(
                    ManualIntegrationEnvironment.dbUrl(), props)) {
                conn.createStatement().execute("USE `" + alt + "`");
                ScriptUtils.executeSqlScript(conn, new EncodedResource(
                        new ClassPathResource("db/upgrade/002_social_consistency.sql"),
                        StandardCharsets.UTF_8));
            }

            Integer likeTable = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = ? AND table_name = 'tb_blog_like'",
                    Integer.class, alt);
            assertThat(likeTable).isEqualTo(1);
            Integer likeRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `" + alt + "`.`tb_blog_like`", Integer.class);
            assertThat(likeRows).isEqualTo(0);
            Integer badLiked = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `" + alt + "`.`tb_blog` WHERE liked IS NULL OR liked <> 0",
                    Integer.class);
            assertThat(badLiked).isEqualTo(0);
            Integer blogCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `" + alt + "`.`tb_blog`", Integer.class);
            assertThat(blogCount).isEqualTo(2);
            Integer legacyComments = jdbcTemplate.queryForObject(
                    "SELECT comments FROM `" + alt + "`.`tb_blog` WHERE id = 1", Integer.class);
            assertThat(legacyComments).isEqualTo(104);
            Integer legacyTitle = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `" + alt + "`.`tb_blog` WHERE id = 1 AND title = 'legacy-1'",
                    Integer.class);
            assertThat(legacyTitle).isEqualTo(1);

            Integer selfFollows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `" + alt + "`.`tb_follow` WHERE user_id = follow_user_id",
                    Integer.class);
            assertThat(selfFollows).isEqualTo(0);
            Integer duplicatePair = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `" + alt + "`.`tb_follow` WHERE user_id = 102 AND follow_user_id = 103",
                    Integer.class);
            assertThat(duplicatePair).isEqualTo(1);

            for (String[] index : new String[][]{
                    {"tb_follow", "uk_follow_user_target"},
                    {"tb_follow", "idx_follow_target_user"},
                    {"tb_blog_like", "uk_blog_like"},
                    {"tb_blog_like", "idx_blog_like_blog_time"},
                    {"tb_blog_like", "idx_blog_like_user_time"},
                    {"tb_blog", "idx_blog_user_create_id"}}) {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                                + "WHERE table_schema = ? AND table_name = ? AND index_name = ?",
                        Integer.class, alt, index[0], index[1]);
                assertThat(count)
                        .as("migration 后索引 %s.%s 必须存在", index[0], index[1])
                        .isEqualTo(1);
            }
        } finally {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS `" + alt + "`");
        }
    }

    @Test
    void freshDatabaseHasSocialSchemaContract() {
        String schema = ManualIntegrationEnvironment.dbSchemaName();
        assertThat(schema).isNotBlank();

        Integer likeTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = 'tb_blog_like'",
                Integer.class, schema);
        assertThat(likeTables).isEqualTo(1);

        for (String[] index : new String[][]{
                {"tb_follow", "uk_follow_user_target"},
                {"tb_follow", "idx_follow_target_user"},
                {"tb_blog_like", "uk_blog_like"},
                {"tb_blog_like", "idx_blog_like_blog_time"},
                {"tb_blog_like", "idx_blog_like_user_time"},
                {"tb_blog", "idx_blog_user_create_id"}}) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                            + "WHERE table_schema = ? AND table_name = ? AND index_name = ?",
                    Integer.class, schema, index[0], index[1]);
            assertThat(count)
                    .as("索引 %s.%s 必须存在", index[0], index[1])
                    .isEqualTo(1);
        }
    }

    @Test
    void concurrentDuplicateFollowKeepsExactlyOneRow() throws Exception {
        long a = nextUserId();
        long b = nextUserId();
        insertUser(a);
        insertUser(b);
        int threads = 8;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < threads; i++) {
                es.submit(() -> {
                    try {
                        login(a);
                        ready.countDown();
                        go.await();
                        results.add(followService.follow(b, true));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        UserContext.clear();
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(results).allSatisfy(r -> assertThat(r.getSuccess()).isTrue());
            assertThat(followCount(a, b)).isEqualTo(1);
        } finally {
            es.shutdownNow();
            deleteFollow(a, b);
            deleteUsers(a, b);
        }
    }

    @Test
    void selfFollowRejectedAndZeroRows() {
        long a = nextUserId();
        insertUser(a);
        try {
            login(a);
            Result result = followService.follow(a, true);
            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).contains("不能关注自己");
            assertThat(followCount(a, a)).isEqualTo(0);
        } finally {
            deleteUsers(a);
        }
    }

    @Test
    void repeatUnfollowIsIdempotent() {
        long a = nextUserId();
        long b = nextUserId();
        insertUser(a);
        insertUser(b);
        try {
            login(a);
            assertThat(followService.follow(b, true).getSuccess()).isTrue();
            assertThat(followService.follow(b, false).getSuccess()).isTrue();
            assertThat(followService.follow(b, false).getSuccess()).isTrue();
            assertThat(followCount(a, b)).isEqualTo(0);
        } finally {
            deleteFollow(a, b);
            deleteUsers(a, b);
        }
    }

    @Test
    void commonFollowsComeFromMySql() {
        long a = nextUserId();
        long b = nextUserId();
        long c = nextUserId();
        long d = nextUserId();
        long e = nextUserId();
        insertUser(a);
        insertUser(b);
        insertUser(c);
        insertUser(d);
        insertUser(e);
        try {
            login(a);
            assertThat(followService.follow(c, true).getSuccess()).isTrue();
            assertThat(followService.follow(d, true).getSuccess()).isTrue();
            login(b);
            assertThat(followService.follow(c, true).getSuccess()).isTrue();
            assertThat(followService.follow(e, true).getSuccess()).isTrue();
            login(a);

            Result common = followService.followCommons(b);
            assertThat(common.getSuccess()).isTrue();
            assertThat(userIds(common)).containsExactly(c);

            login(b);
            assertThat(followService.follow(d, true).getSuccess()).isTrue();
            login(a);
            Result commonTwo = followService.followCommons(b);
            assertThat(userIds(commonTwo)).containsExactly(c, d);
        } finally {
            deleteFollowRows(a, b, c, d, e);
            deleteUsers(a, b, c, d, e);
        }
    }

    @Test
    void sameUserSameBlogConcurrentToggleIsSerialAndConsistent() throws Exception {
        long a = nextUserId();
        long blogId = nextBlogId();
        insertUser(a);
        insertBlog(blogId, a, LocalDateTime.of(2026, 1, 1, 9, 0, 0));
        int threads = 2;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < threads; i++) {
                es.submit(() -> {
                    try {
                        login(a);
                        ready.countDown();
                        go.await();
                        results.add(blogService.likeBlog(blogId));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        UserContext.clear();
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(results).allSatisfy(r -> assertThat(r.getSuccess()).isTrue());
            // 两次切换串行：加一次再减一次，最终未点赞、计数不变、明细为空
            assertThat(likeDetailCount(blogId)).isEqualTo(0);
            assertThat(blogLiked(blogId)).isEqualTo(0);
        } finally {
            es.shutdownNow();
            deleteBlogAndLikes(blogId);
            deleteUsers(a);
        }
    }

    @Test
    void differentUsersConcurrentLikeMatchesDetailCount() throws Exception {
        long blogId = nextBlogId();
        long author = nextUserId();
        insertUser(author);
        insertBlog(blogId, author, LocalDateTime.of(2026, 1, 1, 9, 0, 0));
        int n = 6;
        long[] users = new long[n];
        for (int i = 0; i < n; i++) {
            users[i] = nextUserId();
            insertUser(users[i]);
        }
        ExecutorService es = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < n; i++) {
                long u = users[i];
                es.submit(() -> {
                    try {
                        login(u);
                        ready.countDown();
                        go.await();
                        results.add(blogService.likeBlog(blogId));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        UserContext.clear();
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(results).allSatisfy(r -> assertThat(r.getSuccess()).isTrue());
            assertThat(likeDetailCount(blogId)).isEqualTo(n);
            assertThat(blogLiked(blogId)).isEqualTo(n);
        } finally {
            es.shutdownNow();
            deleteBlogAndLikes(blogId);
            deleteUsers(users);
        }
    }

    @Test
    void cancelLikeNeverUnderflows() {
        long a = nextUserId();
        long blogId = nextBlogId();
        insertUser(a);
        insertBlog(blogId, a, LocalDateTime.of(2026, 1, 1, 9, 0, 0));
        try {
            login(a);
            assertThat(blogService.likeBlog(blogId).getSuccess()).isTrue();
            assertThat(likeDetailCount(blogId)).isEqualTo(1);
            assertThat(blogLiked(blogId)).isEqualTo(1);

            // 模拟计数漂移为 0：取消路径必须整体回滚，计数不得变负
            jdbcTemplate.update("UPDATE tb_blog SET liked = 0 WHERE id = ?", blogId);

            assertThatThrownBy(() -> blogService.likeBlog(blogId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("下溢");
            assertThat(blogLiked(blogId)).isEqualTo(0);
            assertThat(likeDetailCount(blogId)).isEqualTo(1);
        } finally {
            deleteBlogAndLikes(blogId);
            deleteUsers(a);
        }
    }

    @Test
    void top5LikedUsersOrderByCreateTimeThenId() {
        long blogId = nextBlogId();
        long author = nextUserId();
        insertUser(author);
        insertBlog(blogId, author, LocalDateTime.of(2026, 1, 1, 9, 0, 0));
        long[] users = new long[7];
        for (int i = 0; i < users.length; i++) {
            users[i] = nextUserId();
            insertUser(users[i]);
        }
        try {
            LocalDateTime base = LocalDateTime.of(2026, 2, 1, 10, 0, 0);
            for (int i = 0; i < users.length; i++) {
                insertBlogLike(blogId, users[i], base.plusSeconds(i));
            }
            jdbcTemplate.update("UPDATE tb_blog SET liked = ? WHERE id = ?", users.length, blogId);
            login(users[0]);

            Result result = blogService.queryBlogLikes(blogId);

            assertThat(result.getSuccess()).isTrue();
            assertThat(userIds(result))
                    .containsExactly(users[0], users[1], users[2], users[3], users[4]);
        } finally {
            deleteBlogAndLikes(blogId);
            deleteUsers(users);
            deleteUsers(author);
        }
    }

    @Test
    void saveBlogCreatesBlogWithoutAnyFeedWrite() {
        long a = nextUserId();
        insertUser(a);
        String feedKey = "feed:" + a;
        try {
            login(a);
            stringRedisTemplate.opsForZSet().add(feedKey, "123456789", 1d);
            Set<String> before = stringRedisTemplate.keys("feed:*");
            assertThat(before).isNotNull().contains(feedKey);

            Blog blog = new Blog();
            blog.setShopId(1L);
            blog.setTitle("017jd blog");
            blog.setImages("/imgs/017jd.jpg");
            blog.setContent("content");
            Result result = blogService.saveBlog(blog);

            assertThat(result.getSuccess()).isTrue();
            Long blogId = (Long) result.getData();
            assertThat(blogId).isNotNull();
            assertThat(blogLiked(blogId)).isEqualTo(0);

            Set<String> after = stringRedisTemplate.keys("feed:*");
            assertThat(after).isNotNull().isEqualTo(before);
            assertThat(stringRedisTemplate.opsForZSet().score(feedKey, "123456789")).isEqualTo(1d);

            jdbcTemplate.update("DELETE FROM tb_blog WHERE id = ?", blogId);
        } finally {
            deleteRedisKeys(feedKey);
            deleteUsers(a);
        }
    }

    @Test
    void followFeedComesFromMySqlWithScrollPagination() {
        long a = nextUserId();
        long b = nextUserId();
        insertUser(a);
        insertUser(b);
        long b1 = nextBlogId();
        long b2 = nextBlogId();
        long b3 = nextBlogId();
        long b4 = nextBlogId();
        long b5 = nextBlogId();
        try {
            login(a);
            assertThat(followService.follow(b, true).getSuccess()).isTrue();
            insertBlog(b1, b, LocalDateTime.of(2026, 1, 1, 10, 0, 0));
            insertBlog(b2, b, LocalDateTime.of(2026, 1, 1, 11, 0, 0));
            insertBlog(b3, b, LocalDateTime.of(2026, 1, 1, 12, 0, 0));
            insertBlog(b4, b, LocalDateTime.of(2026, 1, 1, 13, 0, 0));
            insertBlog(b5, b, LocalDateTime.of(2026, 1, 1, 13, 0, 0));

            Result page1 = blogService.queryBlogOfFollow(nowMillis(), 0);
            assertThat(feedIds(page1)).containsExactly(b5, b4);
            ScrollResult s1 = (ScrollResult) page1.getData();
            assertThat(s1.getMinTime()).isEqualTo(millis(LocalDateTime.of(2026, 1, 1, 13, 0, 0)));
            assertThat(s1.getOffset()).isEqualTo(2);

            Result page2 = blogService.queryBlogOfFollow(s1.getMinTime(), s1.getOffset());
            assertThat(feedIds(page2)).containsExactly(b3, b2);
            ScrollResult s2 = (ScrollResult) page2.getData();
            assertThat(s2.getMinTime()).isEqualTo(millis(LocalDateTime.of(2026, 1, 1, 11, 0, 0)));
            assertThat(s2.getOffset()).isEqualTo(1);

            Result page3 = blogService.queryBlogOfFollow(s2.getMinTime(), s2.getOffset());
            assertThat(feedIds(page3)).containsExactly(b1);
            ScrollResult s3 = (ScrollResult) page3.getData();
            assertThat(s3.getMinTime()).isEqualTo(millis(LocalDateTime.of(2026, 1, 1, 10, 0, 0)));
            assertThat(s3.getOffset()).isEqualTo(1);

            Result page4 = blogService.queryBlogOfFollow(s3.getMinTime(), s3.getOffset());
            assertThat(feedIds(page4)).isEmpty();
        } finally {
            deleteFollow(a, b);
            deleteBlogAndLikes(b1);
            deleteBlogAndLikes(b2);
            deleteBlogAndLikes(b3);
            deleteBlogAndLikes(b4);
            deleteBlogAndLikes(b5);
            deleteUsers(a, b);
        }
    }

    @Test
    void oldSocialRedisKeysDoNotAffectMySqlQueries() {
        long a = nextUserId();
        long b = nextUserId();
        long blogId = nextBlogId();
        long l1 = nextUserId();
        long l2 = nextUserId();
        long l3 = nextUserId();
        insertUser(a);
        insertUser(b);
        insertUser(l1);
        insertUser(l2);
        insertUser(l3);
        String feedKey = "feed:" + a;
        String followsKey = "follows:" + a;
        String likedKey = "blog:liked:" + blogId;
        try {
            login(a);
            assertThat(followService.follow(b, true).getSuccess()).isTrue();
            insertBlog(blogId, b, LocalDateTime.of(2026, 1, 1, 10, 0, 0));
            insertBlogLike(blogId, l1, LocalDateTime.of(2026, 1, 1, 11, 0, 0));
            insertBlogLike(blogId, l2, LocalDateTime.of(2026, 1, 1, 11, 0, 1));
            insertBlogLike(blogId, l3, LocalDateTime.of(2026, 1, 1, 11, 0, 2));
            jdbcTemplate.update("UPDATE tb_blog SET liked = 3 WHERE id = ?", blogId);

            // 注入旧社交 Redis Key：正式查询必须完全忽略
            stringRedisTemplate.opsForZSet().add(feedKey, "999999999", 1d);
            stringRedisTemplate.opsForSet().add(followsKey, "888888888");
            stringRedisTemplate.opsForZSet().add(likedKey, "888888888", 1d);

            Result feed = blogService.queryBlogOfFollow(nowMillis(), 0);
            assertThat(feedIds(feed)).containsExactly(blogId);

            Result likes = blogService.queryBlogLikes(blogId);
            assertThat(userIds(likes)).containsExactly(l1, l2, l3);

            Result isFollow = followService.isFollow(b);
            assertThat(isFollow.getData()).isEqualTo(Boolean.TRUE);

            Result commons = followService.followCommons(b);
            assertThat(userIds(commons)).isEmpty();

            Result detail = blogService.queryBlogById(blogId);
            Blog blog = (Blog) detail.getData();
            assertThat(blog.getIsLike()).isFalse();
        } finally {
            deleteFollow(a, b);
            deleteBlogAndLikes(blogId);
            deleteUsers(a, b, l1, l2, l3);
            deleteRedisKeys(feedKey, followsKey, likedKey);
        }
    }

    @Test
    void manualClassIsNotPartOfDefaultSurefireSuite() {
        String name = getClass().getSimpleName();
        assertThat(name)
                .doesNotStartWith("Test")
                .doesNotEndWith("Test")
                .doesNotEndWith("Tests")
                .doesNotEndWith("TestCase");
    }
}
