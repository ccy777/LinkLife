package com.linklife.merchant.security;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 路径符号链接防护：检查绝对目标路径的<b>所有已存在祖先</b>
 * （文件系统根 → ... → 配置 root → 子目录 → 目标文件），
 * 任一路径段为符号链接即拒绝，且始终使用 NOFOLLOW 语义，不跟随符号链接读取、覆盖或删除。
 * 允许注入 {@link SymlinkPredicate}，使无符号链接创建权限的环境仍能验证祖先符号链接分支。
 */
public final class NoFollowPathGuard {

    private NoFollowPathGuard() {
    }

    /**
     * 默认实现：使用 {@link Files#isSymbolicLink(Path)} 检查所有已存在祖先。
     */
    public static boolean pathContainsSymlink(Path target) {
        return pathContainsSymlink(target, Files::isSymbolicLink);
    }

    /**
     * 可注入实现：按注入的 predicate 判断每个已存在路径段是否为符号链接。
     * 目标路径先绝对化、normalize，再从文件系统根逐段下探；
     * 不存在的路径段跳过（其已存在祖先仍逐一检查）。
     */
    public static boolean pathContainsSymlink(Path target, SymlinkPredicate predicate) {
        Path normalized = target.toAbsolutePath().normalize();
        Path root = normalized.getRoot();
        if (root == null) {
            return false;
        }
        Path current = root;
        if (existsNoFollow(current) && predicate.isSymbolicLink(current)) {
            return true;
        }
        for (Path segment : normalized) {
            current = current.resolve(segment);
            if (existsNoFollow(current) && predicate.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean existsNoFollow(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 符号链接判断接口：默认委托 {@link Files#isSymbolicLink(Path)}，测试可注入纯函数模拟。
     */
    @FunctionalInterface
    public interface SymlinkPredicate {
        boolean isSymbolicLink(Path path);
    }
}
