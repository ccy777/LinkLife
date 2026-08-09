package com.linklife.merchant.service;

import cn.hutool.core.util.StrUtil;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.merchant.config.UploadProperties;
import com.linklife.merchant.security.NoFollowPathGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 图片上传与删除服务：
 * - 上传路径 users/{userId}/{32位十六进制名}.{ext}，删除只允许当前用户操作自身目录；
 * - 扩展名与 MIME 固定匹配；
 * - 同目录临时文件完整写入后原子移动发布，任意失败只清理本次临时文件；
 * - 用户可见失败统一使用 BusinessException。
 */
@Slf4j
@Service
public class UploadService {

    private static final Set<String> ALLOWED_EXTENSIONS =
            new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp"));
    private static final Map<String, String> EXTENSION_TO_MIME = new HashMap<>();

    static {
        EXTENSION_TO_MIME.put("jpg", "image/jpeg");
        EXTENSION_TO_MIME.put("jpeg", "image/jpeg");
        EXTENSION_TO_MIME.put("png", "image/png");
        EXTENSION_TO_MIME.put("webp", "image/webp");
    }

    private static final Pattern HEX32 = Pattern.compile("^[0-9a-f]{32}$");

    private final UploadProperties properties;
    private final NoFollowPathGuard.SymlinkPredicate symlinkPredicate;

    /**
     * 生产 Spring 注入入口：显式标注 @Autowired，避免多构造器场景下
     * Spring 5.2 回退查找无参构造器而失败（No default constructor found）。
     */
    @Autowired
    public UploadService(UploadProperties properties) {
        this(properties, NoFollowPathGuard::pathContainsSymlink);
    }

    /**
     * 可注入符号链接判断，便于无符号链接权限的环境验证祖先符号链接分支。
     */
    UploadService(UploadProperties properties, NoFollowPathGuard.SymlinkPredicate symlinkPredicate) {
        this.properties = properties;
        this.symlinkPredicate = symlinkPredicate;
    }

    /**
     * 保存图片并返回公开 URL；userId 为 null 时 fail-closed。
     */
    public String saveImage(MultipartFile file, Long userId) {
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (StrUtil.isBlank(originalFilename)) {
            throw new BusinessException("文件名不能为空");
        }
        long size = file.getSize();
        if (size <= 0L) {
            throw new BusinessException("上传文件不能为空");
        }
        if (size > properties.getMaxSizeBytes()) {
            throw new BusinessException("上传文件过大");
        }
        String extension = extractExtension(originalFilename);
        String contentType = file.getContentType();
        if (contentType == null || !EXTENSION_TO_MIME.get(extension).equals(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("文件类型与扩展名不匹配");
        }

        Path root = properties.normalizedRootPath();
        Path userDir = root.resolve("users").resolve(String.valueOf(userId)).normalize();
        if (!userDir.startsWith(root)) {
            throw new BusinessException("文件路径不合法");
        }
        String finalName = newRandomHexName() + "." + extension;
        Path finalTarget = userDir.resolve(finalName).normalize();
        String tempName = ".tmp-" + finalName + "-" + UUID.randomUUID().toString().replace("-", "");
        Path tempTarget = userDir.resolve(tempName).normalize();
        try {
            // 上传前：文件系统根到用户目录的所有已存在祖先不得为符号链接（含配置 root 之前的父目录）
            if (NoFollowPathGuard.pathContainsSymlink(userDir, symlinkPredicate)) {
                throw new BusinessException("上传目录不合法");
            }
            Files.createDirectories(userDir);
            // 创建目录后再次检查（目录可能刚被创建，祖先可能被并发替换为符号链接）
            if (NoFollowPathGuard.pathContainsSymlink(tempTarget, symlinkPredicate)) {
                throw new BusinessException("上传目录不合法");
            }
            try (InputStream in = file.getInputStream()) {
                writeFile(in, tempTarget);
            }
            // move 前立即重检：tempTarget 自身若被替换为符号链接、或 finalTarget 父链被替换为符号链接，一律拒绝
            if (NoFollowPathGuard.pathContainsSymlink(tempTarget, symlinkPredicate)
                    || NoFollowPathGuard.pathContainsSymlink(finalTarget, symlinkPredicate)) {
                cleanupTempFile(tempTarget);
                throw new BusinessException("上传目录不合法");
            }
            moveFile(tempTarget, finalTarget);
        } catch (IOException e) {
            cleanupTempFile(tempTarget);
            log.error("文件上传失败 userId={}", userId, e);
            throw new BusinessException("文件上传失败，请稍后再试", e);
        }
        log.debug("文件上传成功 userId={}, name={}", userId, finalName);
        return properties.normalizedPublicPrefix() + "users/" + userId + "/" + finalName;
    }

    /**
     * 删除当前用户自身目录下的文件；不存在的文件视为幂等成功。
     */
    public void deleteImage(String publicUrl, Long userId) {
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        String relative = parseOwnedRelativePath(publicUrl, userId);
        Path root = properties.normalizedRootPath();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("非法的文件路径");
        }
        try {
            // 删除前：文件系统根到目标的所有已存在祖先不得为符号链接；目标必须是普通文件
            if (NoFollowPathGuard.pathContainsSymlink(target, symlinkPredicate)) {
                throw new BusinessException("上传目录不合法");
            }
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException("不允许删除目录");
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException("不允许删除非普通文件");
            }
            deleteFile(target);
        } catch (IOException e) {
            log.error("文件删除失败 userId={}", userId, e);
            throw new BusinessException("文件删除失败，请稍后再试", e);
        }
    }

    /**
     * 解析公开 URL 并严格校验为 users/{当前userId}/{32hex}.{ext}。
     */
    private String parseOwnedRelativePath(String publicUrl, Long userId) {
        if (StrUtil.isBlank(publicUrl)) {
            throw new BusinessException("文件路径不能为空");
        }
        String prefix = properties.normalizedPublicPrefix();
        String path = publicUrl.startsWith("/") ? publicUrl.substring(1) : publicUrl;
        String prefixWithoutLeadingSlash = prefix.substring(1);
        if (!path.startsWith(prefixWithoutLeadingSlash)) {
            throw new BusinessException("非法的文件路径");
        }
        String relative = path.substring(prefixWithoutLeadingSlash.length());
        if (StrUtil.isBlank(relative)) {
            throw new BusinessException("非法的文件路径");
        }
        String lower = relative.toLowerCase(Locale.ROOT);
        if (relative.contains("..")
                || lower.contains("%2e")
                || relative.contains("\\")
                || relative.contains(":")) {
            throw new BusinessException("非法的文件路径");
        }
        String[] parts = relative.split("/");
        if (parts.length != 3) {
            throw new BusinessException("非法的文件路径");
        }
        if (!"users".equals(parts[0])) {
            throw new BusinessException("非法的文件路径");
        }
        if (!parts[1].equals(String.valueOf(userId))) {
            throw new BusinessException("非法的文件路径");
        }
        int dot = parts[2].lastIndexOf('.');
        if (dot <= 0 || dot == parts[2].length() - 1) {
            throw new BusinessException("非法的文件路径");
        }
        String name = parts[2].substring(0, dot);
        String extension = parts[2].substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!HEX32.matcher(name).matches() || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("非法的文件路径");
        }
        return relative;
    }

    private String extractExtension(String originalFilename) {
        String normalized = originalFilename.replace('\\', '/');
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot < 0 || lastDot == normalized.length() - 1) {
            throw new BusinessException("仅支持 jpg/jpeg/png/webp 图片");
        }
        String base = normalized.substring(0, lastDot);
        if (StrUtil.isBlank(base)) {
            throw new BusinessException("仅支持 jpg/jpeg/png/webp 图片");
        }
        String extension = normalized.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("仅支持 jpg/jpeg/png/webp 图片");
        }
        return extension;
    }

    /**
     * 生成 32 位随机十六进制文件名（可测试注入）。
     */
    protected String newRandomHexName() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 可测试的最小写入口：完整写入临时文件。
     */
    protected void writeFile(InputStream in, Path tempTarget) throws IOException {
        Files.copy(in, tempTarget);
    }

    /**
     * 可测试的最小移动入口：优先 ATOMIC_MOVE，失败回退为不覆盖既有文件的普通 MOVE。
     * 先拒绝既有目标文件，避免 Windows 上 ATOMIC_MOVE 可能覆盖旧文件。
     */
    protected void moveFile(Path tempTarget, Path finalTarget) throws IOException {
        if (Files.exists(finalTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(finalTarget.toString());
        }
        try {
            Files.move(tempTarget, finalTarget, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempTarget, finalTarget);
        }
    }

    /**
     * 可测试的最小删入口。
     */
    protected void deleteFile(Path target) throws IOException {
        Files.deleteIfExists(target);
    }

    /**
     * 安全清理临时文件：删除前先执行 NOFOLLOW 祖先检查；
     * tempTarget 自身或任一已存在祖先为符号链接时，无法确认删除路径安全，不跨符号链接删除，只告警。
     */
    private void cleanupTempFile(Path tempTarget) {
        if (tempTarget == null) {
            return;
        }
        try {
            if (NoFollowPathGuard.pathContainsSymlink(tempTarget, symlinkPredicate)) {
                log.warn("临时文件路径含符号链接，拒绝删除，交由人工处理");
                return;
            }
            Files.deleteIfExists(tempTarget);
        } catch (IOException deleteEx) {
            log.warn("清理临时上传文件失败", deleteEx);
        }
    }
}
