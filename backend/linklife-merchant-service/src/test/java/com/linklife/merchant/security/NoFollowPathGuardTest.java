package com.linklife.merchant.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NoFollowPathGuard 纯函数测试：不依赖 OS 符号链接权限，
 * 通过注入 predicate 模拟“配置 root 的父目录为符号链接”等祖先分支。
 */
class NoFollowPathGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void cleanPathIsAccepted() throws Exception {
        Path target = tempDir.resolve("users/1/photo.jpg");
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[]{1});

        // 注入恒 false predicate：不依赖宿主机临时目录祖先是否为真实符号链接；
        // 真实符号链接分支由显式 Assumption 测试覆盖
        List<Path> observed = new ArrayList<>();
        NoFollowPathGuard.SymlinkPredicate neverSymlink = path -> {
            observed.add(path);
            return false;
        };

        assertThat(NoFollowPathGuard.pathContainsSymlink(target, neverSymlink)).isFalse();
        assertThat(observed).contains(tempDir.toAbsolutePath().normalize());
        assertThat(observed).contains(target.toAbsolutePath().normalize());
    }

    @Test
    void parentOfConfiguredRootMarkedAsSymlinkIsRejected() throws Exception {
        // 模拟 /safe/link/uploads 场景：root 自身不是符号链接，但 root 的父目录被标记为符号链接
        Path root = tempDir.resolve("uploads");
        Files.createDirectories(root);
        Path target = root.resolve("users/1/a.jpg");

        NoFollowPathGuard.SymlinkPredicate parentIsLink = path -> path.equals(tempDir);

        assertThat(NoFollowPathGuard.pathContainsSymlink(target, parentIsLink)).isTrue();
    }

    @Test
    void deepAncestorMarkedAsSymlinkIsRejected() throws Exception {
        Path target = tempDir.resolve("a/b/c/d.jpg");
        Files.createDirectories(target.getParent());

        NoFollowPathGuard.SymlinkPredicate deepAncestorIsLink = path -> path.equals(tempDir.resolve("a/b"));

        assertThat(NoFollowPathGuard.pathContainsSymlink(target, deepAncestorIsLink)).isTrue();
    }

    @Test
    void rootItselfMarkedAsSymlinkIsRejected() throws Exception {
        Path root = tempDir.resolve("root-link");
        Files.createDirectories(root);
        Path target = root.resolve("x.jpg");

        NoFollowPathGuard.SymlinkPredicate rootIsLink = path -> path.equals(root);

        assertThat(NoFollowPathGuard.pathContainsSymlink(target, rootIsLink)).isTrue();
    }

    @Test
    void nonExistentLeafStillChecksAllExistingAncestors() throws Exception {
        // 目标文件尚未创建：不存在的路径段跳过，但其已存在祖先仍逐一检查
        Path root = tempDir.resolve("uploads");
        Files.createDirectories(root);
        Path target = root.resolve("users/1/not-yet-created.jpg");

        NoFollowPathGuard.SymlinkPredicate rootParentIsLink = path -> path.equals(tempDir);

        assertThat(NoFollowPathGuard.pathContainsSymlink(target, rootParentIsLink)).isTrue();
    }

    @Test
    void predicateObservesAbsoluteNormalizedExistingAncestors() throws Exception {
        // 传入含 . 与 .. 的目标：predicate 必须观察到 absolute + normalize 后的已存在祖先
        Path target = tempDir.resolve("a/./b/../c.txt");
        Files.createDirectories(tempDir.resolve("a"));
        List<Path> observed = new ArrayList<>();
        NoFollowPathGuard.SymlinkPredicate recorder = path -> {
            observed.add(path);
            return false;
        };

        assertThat(NoFollowPathGuard.pathContainsSymlink(target, recorder)).isFalse();

        assertThat(observed).isNotEmpty();
        for (Path path : observed) {
            assertThat(path.isAbsolute()).as("predicate 必须收到绝对路径").isTrue();
            assertThat(path.normalize()).isEqualTo(path);
            for (Path name : path) {
                assertThat(name.toString()).isNotEqualTo(".").isNotEqualTo("..");
            }
        }
        assertThat(observed).contains(tempDir.toAbsolutePath().normalize());
        assertThat(observed).contains(tempDir.resolve("a").toAbsolutePath().normalize());
        // 不存在的叶子 c.txt 不被检查：不要求真实目录树无符号链接
        assertThat(observed).doesNotContain(tempDir.resolve("a/c.txt").toAbsolutePath().normalize());
    }

    @Test
    void dotDotSegmentsResolvedBeforeAncestorCheck() throws Exception {
        // 目标含 ..：normalize 后命中 tempDir/sub，predicate 必须观察到该祖先，且不观察被消解的 a
        Path target = tempDir.resolve("a/../sub/file.txt");
        Files.createDirectories(tempDir.resolve("sub"));
        List<Path> observed = new ArrayList<>();
        NoFollowPathGuard.SymlinkPredicate flagSub = path -> {
            observed.add(path);
            return path.equals(tempDir.resolve("sub").toAbsolutePath().normalize());
        };

        assertThat(NoFollowPathGuard.pathContainsSymlink(target, flagSub)).isTrue();
        assertThat(observed).contains(tempDir.resolve("sub").toAbsolutePath().normalize());
        assertThat(observed).doesNotContain(tempDir.resolve("a").toAbsolutePath().normalize());
    }

    @Test
    void predicateNeverReceivesNonExistentLeaf() throws Exception {
        Path target = tempDir.resolve("users/1/not-created.jpg");
        Files.createDirectories(tempDir.resolve("users/1"));
        List<Path> observed = new ArrayList<>();
        NoFollowPathGuard.SymlinkPredicate recorder = path -> {
            observed.add(path);
            return false;
        };

        assertThat(NoFollowPathGuard.pathContainsSymlink(target, recorder)).isFalse();
        assertThat(observed).contains(tempDir.resolve("users/1").toAbsolutePath().normalize());
        assertThat(observed).doesNotContain(target.toAbsolutePath().normalize());
    }

    @Test
    void walkStartsAtFilesystemRoot() throws Exception {
        Path target = tempDir.resolve("a.txt");
        Files.write(target, new byte[]{1});
        List<Path> observed = new ArrayList<>();
        NoFollowPathGuard.SymlinkPredicate recorder = path -> {
            observed.add(path);
            return false;
        };

        NoFollowPathGuard.pathContainsSymlink(target, recorder);

        assertThat(observed).contains(target.toAbsolutePath().normalize().getRoot());
        assertThat(observed).contains(target.toAbsolutePath().normalize());
    }

    @Test
    void guardUsesNoFollowLinksAndAbsoluteNormalize() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/merchant/security/NoFollowPathGuard.java")), StandardCharsets.UTF_8);

        assertThat(source).contains("LinkOption.NOFOLLOW_LINKS");
        assertThat(source).contains("toAbsolutePath().normalize()");
        assertThat(source).contains("Files.exists(path, LinkOption.NOFOLLOW_LINKS)");
    }
}
