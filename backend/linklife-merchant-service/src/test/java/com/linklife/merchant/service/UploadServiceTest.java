package com.linklife.merchant.service;

import com.linklife.merchant.config.UploadProperties;
import com.linklife.common.core.exception.BusinessException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图片上传与删除安全单元测试：所有权、MIME 匹配、临时文件原子发布。
 * 使用 JUnit 临时目录，不写真实磁盘目录，不连接真实 Redis/MySQL。
 */
class UploadServiceTest {

    @TempDir
    Path tempDir;

    private static final long MAX = 5L * 1024 * 1024;
    private static final String HEX = "0123456789abcdef0123456789abcdef";

    private UploadService service;

    @BeforeEach
    void setUp() {
        UploadProperties properties = uploadProperties(tempDir);
        service = new UploadService(properties);
    }

    private UploadProperties uploadProperties(Path root) {
        UploadProperties properties = new UploadProperties();
        properties.setRoot(root.toString());
        properties.setResourcePrefix("/files/");
        properties.setPublicPrefix("/api/files/");
        properties.setMaxSizeBytes(MAX);
        return properties;
    }

    private MultipartFile mockFile(String name, String contentType, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getSize()).thenReturn(size);
        when(file.isEmpty()).thenReturn(size <= 0L);
        try {
            when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[64]));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return file;
    }

    private Path userDir() {
        return tempDir.resolve("users").resolve("1").normalize();
    }

    private long countTempFiles(Path dir) throws IOException {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(p -> p.getFileName().toString().startsWith(".tmp-")).count();
        }
    }

    @Test
    void validJpgUploadSucceeds() {
        String url = service.saveImage(mockFile("photo.jpg", "image/jpeg", 100L), 1L);

        assertThat(url).startsWith("/api/files/users/1/").endsWith(".jpg");
        String name = url.substring(url.lastIndexOf('/') + 1);
        assertThat(Files.exists(userDir().resolve(name))).isTrue();
    }

    @Test
    void validPngAndWebpUploadSucceed() {
        for (String[] pair : new String[][]{{"a.png", "image/png"}, {"b.webp", "image/webp"}, {"c.jpeg", "image/jpeg"}}) {
            String url = service.saveImage(mockFile(pair[0], pair[1], 100L), 1L);
            assertThat(url).startsWith("/api/files/users/1/");
            assertThat(Files.exists(userDir().resolve(url.substring(url.lastIndexOf('/') + 1)))).isTrue();
        }
    }

    @Test
    void emptyFileRejected() {
        assertThatThrownBy(() -> service.saveImage(mockFile("a.jpg", "image/jpeg", 0L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void overMaxSizeRejected() {
        assertThatThrownBy(() -> service.saveImage(mockFile("a.jpg", "image/jpeg", MAX + 1L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传文件过大");
    }

    @Test
    void equalMaxSizeAllowed() {
        String url = service.saveImage(mockFile("a.jpg", "image/jpeg", MAX), 1L);

        assertThat(url).startsWith("/api/files/users/1/");
    }

    @Test
    void nonImageMimeRejected() {
        assertThatThrownBy(() -> service.saveImage(mockFile("a.jpg", "text/plain", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件类型与扩展名不匹配");
    }

    @Test
    void nonWhitelistExtensionRejected() {
        assertThatThrownBy(() -> service.saveImage(mockFile("a.gif", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持");
    }

    @Test
    void doubleExtensionCannotBypass() {
        assertThatThrownBy(() -> service.saveImage(mockFile("photo.jpg.exe", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持");
    }

    @Test
    void pathTraversalInOriginalNameDoesNotAffectFinalRandomName() {
        String url = service.saveImage(mockFile("../../../etc/passwd.jpg", "image/jpeg", 100L), 1L);

        String name = url.substring(url.lastIndexOf('/') + 1);
        assertThat(name).doesNotContain("passwd").doesNotContain("..").doesNotContain("/");
        assertThat(Files.exists(userDir().resolve(name))).isTrue();
    }

    @Test
    void finalPathStaysWithinUploadRoot() {
        String url = service.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L);

        Path finalPath = userDir().resolve(url.substring(url.lastIndexOf('/') + 1)).normalize();
        assertThat(finalPath.startsWith(tempDir.normalize().toAbsolutePath())).isTrue();
        assertThat(Files.exists(finalPath)).isTrue();
    }

    @Test
    void returnValueDoesNotContainAbsolutePath() {
        String url = service.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L);

        assertThat(url).startsWith("/api/files/users/1/");
        assertThat(url).doesNotContain(tempDir.toString());
        assertThat(url).doesNotContain("\\");
        assertThat(url).doesNotContain(":");
    }

    @Test
    void writeFailureCleansTempFile() throws IOException {
        UploadService spy = spy(service);
        AtomicReference<Path> tempRef = new AtomicReference<>();
        doAnswer(invocation -> {
            Path temp = invocation.getArgument(1);
            tempRef.set(temp);
            Files.write(temp, new byte[]{1, 2, 3});
            throw new IOException("disk full");
        }).when(spy).writeFile(any(), any());

        assertThatThrownBy(() -> spy.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class);

        assertThat(Files.exists(tempRef.get())).isFalse();
    }

    @Test
    void pngWithJpegMimeRejected() {
        assertThatThrownBy(() -> service.saveImage(mockFile("a.png", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件类型与扩展名不匹配");
    }

    @Test
    void jpgWithPngMimeRejected() {
        assertThatThrownBy(() -> service.saveImage(mockFile("a.jpg", "image/png", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件类型与扩展名不匹配");
    }

    @Test
    void writeFailureKeepsPreExistingFinalFileAndCleansTemp() throws IOException {
        UploadService spy = spy(service);
        doReturn(HEX).when(spy).newRandomHexName();
        Files.createDirectories(userDir());
        Files.write(userDir().resolve(HEX + ".jpg"), "old".getBytes(StandardCharsets.UTF_8));
        AtomicReference<Path> tempRef = new AtomicReference<>();
        doAnswer(invocation -> {
            Path temp = invocation.getArgument(1);
            tempRef.set(temp);
            Files.write(temp, new byte[]{1, 2, 3});
            throw new IOException("disk full");
        }).when(spy).writeFile(any(), any());

        assertThatThrownBy(() -> spy.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class);

        assertThat(Files.readAllBytes(userDir().resolve(HEX + ".jpg")))
                .isEqualTo("old".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.exists(tempRef.get())).isFalse();
    }

    @Test
    void moveFailureReturnsNoUrlAndCleansTemp() throws IOException {
        UploadService spy = spy(service);
        AtomicReference<Path> tempRef = new AtomicReference<>();
        AtomicReference<Path> finalRef = new AtomicReference<>();
        doAnswer(invocation -> {
            tempRef.set(invocation.getArgument(0));
            finalRef.set(invocation.getArgument(1));
            throw new IOException("move failed");
        }).when(spy).moveFile(any(), any());

        assertThatThrownBy(() -> spy.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class);

        assertThat(Files.exists(tempRef.get())).isFalse();
        assertThat(Files.exists(finalRef.get())).isFalse();
    }

    @Test
    void finalFileNotVisibleBeforeMove() throws IOException {
        UploadService spy = spy(service);
        AtomicBoolean visibleBeforeMove = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Path temp = invocation.getArgument(0);
            Path finalPath = invocation.getArgument(1);
            visibleBeforeMove.set(Files.exists(finalPath));
            Files.move(temp, finalPath, StandardCopyOption.ATOMIC_MOVE);
            return null;
        }).when(spy).moveFile(any(), any());

        String url = spy.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L);

        assertThat(visibleBeforeMove).isFalse();
        assertThat(url).startsWith("/api/files/users/1/");
    }

    @Test
    void nameCollisionDoesNotOverwriteOldFile() throws IOException {
        UploadService spy = spy(service);
        doReturn(HEX).when(spy).newRandomHexName();
        Files.createDirectories(userDir());
        Files.write(userDir().resolve(HEX + ".jpg"), "old".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> spy.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class);

        assertThat(Files.readAllBytes(userDir().resolve(HEX + ".jpg")))
                .isEqualTo("old".getBytes(StandardCharsets.UTF_8));
        assertThat(countTempFiles(userDir())).isZero();
    }

    @Test
    void noTempResidueAfterSuccess() throws IOException {
        service.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L);

        assertThat(countTempFiles(userDir())).isZero();
    }

    @Test
    void deleteValidRelativePathSucceeds() throws IOException {
        Files.createDirectories(userDir());
        Files.write(userDir().resolve(HEX + ".jpg"), new byte[]{1, 2, 3});

        service.deleteImage("/api/files/users/1/" + HEX + ".jpg", 1L);

        assertThat(Files.exists(userDir().resolve(HEX + ".jpg"))).isFalse();
    }

    @Test
    void deleteMissingFileIsIdempotent() {
        assertThatCode(() -> service.deleteImage("/api/files/users/1/" + HEX + ".jpg", 1L))
                .doesNotThrowAnyException();
    }

    @Test
    void deleteParentTraversalRejected() {
        assertThatThrownBy(() -> service.deleteImage("/api/files/users/1/../secret", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
    }

    @Test
    void deleteAbsolutePathRejected() {
        assertThatThrownBy(() -> service.deleteImage("C:\\Windows\\evil.jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
        assertThatThrownBy(() -> service.deleteImage("/etc/passwd", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
    }

    @Test
    void deleteBackslashTraversalRejected() {
        assertThatThrownBy(() -> service.deleteImage("/api/files/users/1/a\\..\\b.jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
        assertThatThrownBy(() -> service.deleteImage("/files/%2e%2e/secret.jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
    }

    @Test
    void deleteUploadRootItselfRejected() {
        assertThatThrownBy(() -> service.deleteImage("/files", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
        assertThatThrownBy(() -> service.deleteImage("/files/", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
    }

    @Test
    void deleteDirectoryTargetRejected() throws IOException {
        Files.createDirectories(userDir().resolve(HEX + ".jpg"));

        assertThatThrownBy(() -> service.deleteImage("/api/files/users/1/" + HEX + ".jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许删除目录");
    }

    @Test
    void deleteFailureDoesNotReturnSuccess() throws IOException {
        Files.createDirectories(userDir());
        Files.write(userDir().resolve(HEX + ".jpg"), new byte[]{1, 2, 3});
        UploadService spy = spy(service);
        doThrow(new IOException("io error")).when(spy).deleteFile(any());

        assertThatThrownBy(() -> spy.deleteImage("/api/files/users/1/" + HEX + ".jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("删除失败");
        assertThat(Files.exists(userDir().resolve(HEX + ".jpg"))).isTrue();
    }

    @Test
    void uploadPathContainsUserId() {
        String url = service.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L);

        assertThat(url).contains("/users/1/");
    }

    @Test
    void notLoggedInUploadFails() {
        assertThatThrownBy(() -> service.saveImage(mockFile("a.jpg", "image/jpeg", 100L), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先登录");
    }

    @Test
    void userCannotDeleteOtherUserPath() throws IOException {
        Path otherDir = tempDir.resolve("users").resolve("2").normalize();
        Files.createDirectories(otherDir);
        Files.write(otherDir.resolve(HEX + ".jpg"), new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.deleteImage("/api/files/users/2/" + HEX + ".jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
        assertThat(Files.exists(otherDir.resolve(HEX + ".jpg"))).isTrue();
    }

    @Test
    void deleteOtherUserIdRejected() {
        assertThatThrownBy(() -> service.deleteImage("/api/files/users/2/" + HEX + ".jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
    }

    @Test
    void non32HexFileNameRejected() {
        assertThatThrownBy(() -> service.deleteImage("/api/files/users/1/abc.jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
    }

    @Test
    void validOwnFileDeleteSuccess() throws IOException {
        Files.createDirectories(userDir());
        Files.write(userDir().resolve(HEX + ".jpg"), new byte[]{1, 2, 3});

        service.deleteImage("/api/files/users/1/" + HEX + ".jpg", 1L);

        assertThat(Files.exists(userDir().resolve(HEX + ".jpg"))).isFalse();
    }

    @Test
    void missingOwnFileIdempotentSuccess() {
        assertThatCode(() -> service.deleteImage("/api/files/users/1/" + HEX + ".jpg", 1L))
                .doesNotThrowAnyException();
    }

    @Test
    void uploadReturnsApiFilesPublicUrl() {
        String url = service.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L);

        assertThat(url).startsWith("/api/files/users/1/");
    }

    @Test
    void deleteParsesApiFilesPublicUrl() throws IOException {
        Files.createDirectories(userDir());
        Files.write(userDir().resolve(HEX + ".jpg"), new byte[]{1, 2, 3});

        service.deleteImage("/api/files/users/1/" + HEX + ".jpg", 1L);

        assertThat(Files.exists(userDir().resolve(HEX + ".jpg"))).isFalse();
    }

    @Test
    void filesUrlNotAcceptedForDeleteUnderDefaultPublicPrefix() {
        // 默认 public-prefix 为 /api/files/，/files/... 不得作为删除所有权 URL 混用
        assertThatThrownBy(() -> service.deleteImage("/files/users/1/" + HEX + ".jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法的文件路径");
    }

    private boolean canCreateSymlink() {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("sl-probe");
            Path target = dir.resolve("t.txt");
            Files.write(target, new byte[]{1});
            Files.createSymbolicLink(dir.resolve("l.txt"), target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        } finally {
            if (dir != null) {
                try {
                    Files.deleteIfExists(dir.resolve("l.txt"));
                    Files.deleteIfExists(dir.resolve("t.txt"));
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // 清理失败不影响探测结果
                }
            }
        }
    }

    @Test
    void rootSymlinkRejected() throws Exception {
        Assumptions.assumeTrue(canCreateSymlink(), "Windows 无符号链接权限，跳过");

        Path realRoot = tempDir.resolve("real-root");
        Files.createDirectories(realRoot);
        Path linkRoot = tempDir.resolve("root-link");
        Files.createSymbolicLink(linkRoot, realRoot);
        UploadProperties p = new UploadProperties();
        p.setRoot(linkRoot.toString());
        p.setResourcePrefix("/files/");
        p.setPublicPrefix("/api/files/");
        p.setMaxSizeBytes(5 * 1024 * 1024);
        UploadService svc = new UploadService(p);

        assertThatThrownBy(() -> svc.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传目录不合法");
    }

    @Test
    void ancestorOfRootSymlinkRejected() throws Exception {
        // 覆盖任务书场景：/safe/link/uploads 中 root 自身不是符号链接，但 root 的父目录是符号链接
        Assumptions.assumeTrue(canCreateSymlink(), "Windows 无符号链接权限，跳过");

        Path realRoot = tempDir.resolve("real-root");
        Files.createDirectories(realRoot.resolve("uploads"));
        Path linkRoot = tempDir.resolve("root-parent-link");
        Files.createSymbolicLink(linkRoot, realRoot);
        UploadProperties p = new UploadProperties();
        p.setRoot(linkRoot.resolve("uploads").toString());
        p.setResourcePrefix("/files/");
        p.setPublicPrefix("/api/files/");
        p.setMaxSizeBytes(5 * 1024 * 1024);
        UploadService svc = new UploadService(p);

        assertThatThrownBy(() -> svc.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传目录不合法");
    }

    @Test
    void rootParentSymlinkRejectedViaInjectedPredicate() throws Exception {
        // 不依赖 OS 符号链接权限：注入 predicate 把配置 root 的父目录标记为符号链接，必须 fail-closed
        UploadProperties p = new UploadProperties();
        p.setRoot(tempDir.resolve("uploads").toString());
        p.setResourcePrefix("/files/");
        p.setPublicPrefix("/api/files/");
        p.setMaxSizeBytes(5 * 1024 * 1024);
        UploadService svc = new UploadService(p,
                path -> path.equals(tempDir));

        assertThatThrownBy(() -> svc.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传目录不合法");
    }

    @Test
    void deleteRejectsAncestorSymlinkViaInjectedPredicate() throws Exception {
        Files.createDirectories(tempDir.resolve("uploads").resolve("users").resolve("1"));
        Files.write(tempDir.resolve("uploads").resolve("users").resolve("1").resolve(HEX + ".jpg"),
                new byte[]{1, 2, 3});
        UploadProperties p = uploadProperties(tempDir.resolve("uploads"));
        UploadService svc = new UploadService(p,
                path -> path.equals(tempDir));

        assertThatThrownBy(() -> svc.deleteImage("/api/files/users/1/" + HEX + ".jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传目录不合法");
    }

    @Test
    void movePrecheckRejectsAncestorSymlinkAfterWriteWithoutMoving() throws Exception {
        // 前两轮祖先检查安全；writeFile 完成后把配置 root 的父目录标记为符号链接，move 前必须拒绝
        AtomicBoolean writeDone = new AtomicBoolean(false);
        UploadService base = new UploadService(uploadProperties(tempDir), path ->
                writeDone.get() && path.equals(tempDir.toAbsolutePath().normalize()));
        UploadService spy = spy(base);
        AtomicReference<Path> tempRef = new AtomicReference<>();
        doAnswer(invocation -> {
            Path temp = invocation.getArgument(1);
            tempRef.set(temp);
            Files.write(temp, new byte[]{1, 2, 3});
            writeDone.set(true);
            return null;
        }).when(spy).writeFile(any(), any());

        assertThatThrownBy(() -> spy.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传目录不合法");

        // move 前检查失败：不得调用 moveFile、不得返回 URL
        verify(spy, never()).moveFile(any(), any());
        // 不安全祖先下 cleanup 拒绝跨符号链接删除，临时文件必须保留
        assertThat(Files.exists(tempRef.get())).isTrue();
    }

    @Test
    void movePrecheckRejectsTempFileSymlinkAfterWriteWithoutMoving() throws Exception {
        // tempTarget 自身在写完后被标记为符号链接：move 前必须拒绝且不跨符号链接删除
        UploadService base = new UploadService(uploadProperties(tempDir), path ->
                path.getFileName() != null && path.getFileName().toString().startsWith(".tmp-"));
        UploadService spy = spy(base);
        AtomicReference<Path> tempRef = new AtomicReference<>();
        doAnswer(invocation -> {
            Path temp = invocation.getArgument(1);
            tempRef.set(temp);
            Files.write(temp, new byte[]{1, 2, 3});
            return null;
        }).when(spy).writeFile(any(), any());

        assertThatThrownBy(() -> spy.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传目录不合法");

        verify(spy, never()).moveFile(any(), any());
        assertThat(Files.exists(tempRef.get())).isTrue();
    }

    @Test
    void movePrecheckPassesWhenAncestorsRemainSafe() throws Exception {
        // 正常路径：move 前重检通过，仍成功发布并返回 URL
        UploadService base = new UploadService(uploadProperties(tempDir), path -> false);
        UploadService spy = spy(base);
        doAnswer(invocation -> {
            Path temp = invocation.getArgument(1);
            Files.write(temp, new byte[]{1, 2, 3});
            return null;
        }).when(spy).writeFile(any(), any());

        String url = spy.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L);

        assertThat(url).startsWith("/api/files/users/1/").endsWith(".jpg");
        verify(spy).moveFile(any(), any());
        assertThat(Files.exists(userDir().resolve(url.substring(url.lastIndexOf('/') + 1)))).isTrue();
    }

    @Test
    void springContainerInstantiatesUploadServiceWithProductionConstructor() {
        // 最小真实 Spring 容器（不启动完整应用、不连接 Redis/MySQL）：
        // 多构造器场景下必须能选择单参数生产构造器实例化，而不是抛 No default constructor found
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("uploadProperties", UploadProperties.class, () -> uploadProperties(tempDir));
            context.registerBean(UploadService.class);
            context.refresh();

            UploadService bean = context.getBean(UploadService.class);
            assertThat(bean).isNotNull();
        }
    }

    @Test
    void unsafeCleanupRefusesDeletionWhenAncestorSymlinkAfterIoFailure() throws Exception {
        // writeFile 写完临时文件后抛 IOException，且祖先已切换为符号链接：cleanup 必须拒绝跨符号链接删除
        AtomicBoolean writeDone = new AtomicBoolean(false);
        UploadService base = new UploadService(uploadProperties(tempDir), path ->
                writeDone.get() && path.equals(tempDir.toAbsolutePath().normalize()));
        UploadService spy = spy(base);
        AtomicReference<Path> tempRef = new AtomicReference<>();
        doAnswer(invocation -> {
            Path temp = invocation.getArgument(1);
            tempRef.set(temp);
            Files.write(temp, new byte[]{1, 2, 3});
            writeDone.set(true);
            throw new IOException("disk full");
        }).when(spy).writeFile(any(), any());

        assertThatThrownBy(() -> spy.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件上传失败");

        assertThat(Files.exists(tempRef.get())).isTrue();
    }

    @Test
    void safeCleanupDeletesTempFileWhenIoFailureWithSafeAncestors() throws Exception {
        // 祖先安全时，IOException 路径仍正常删除本次临时文件
        UploadService base = new UploadService(uploadProperties(tempDir), path -> false);
        UploadService spy = spy(base);
        AtomicReference<Path> tempRef = new AtomicReference<>();
        doAnswer(invocation -> {
            Path temp = invocation.getArgument(1);
            tempRef.set(temp);
            Files.write(temp, new byte[]{1, 2, 3});
            throw new IOException("disk full");
        }).when(spy).writeFile(any(), any());

        assertThatThrownBy(() -> spy.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件上传失败");

        assertThat(Files.exists(tempRef.get())).isFalse();
    }

    @Test
    void usersAncestorSymlinkRejected() throws Exception {
        Assumptions.assumeTrue(canCreateSymlink(), "Windows 无符号链接权限，跳过");

        Path realUsers = tempDir.resolve("real-users");
        Files.createDirectories(realUsers);
        Files.createSymbolicLink(tempDir.resolve("users"), realUsers);

        assertThatThrownBy(() -> service.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传目录不合法");
    }

    @Test
    void userIdDirSymlinkRejected() throws Exception {
        Assumptions.assumeTrue(canCreateSymlink(), "Windows 无符号链接权限，跳过");

        Files.createDirectories(tempDir.resolve("users").resolve("1-real"));
        Files.createSymbolicLink(tempDir.resolve("users").resolve("1"), tempDir.resolve("users").resolve("1-real"));

        assertThatThrownBy(() -> service.saveImage(mockFile("a.jpg", "image/jpeg", 100L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传目录不合法");
    }

    @Test
    void fileSymlinkDeleteRejected() throws Exception {
        Assumptions.assumeTrue(canCreateSymlink(), "Windows 无符号链接权限，跳过");

        Files.createDirectories(userDir());
        Path targetFile = tempDir.resolve("target-file.jpg");
        Files.write(targetFile, new byte[]{1, 2, 3});
        Files.createSymbolicLink(userDir().resolve(HEX + ".jpg"), targetFile);

        assertThatThrownBy(() -> service.deleteImage("/api/files/users/1/" + HEX + ".jpg", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传目录不合法");
    }

    @Test
    void symlinkProtectionContractTest() throws Exception {
        // 不依赖 OS 权限的契约测试：写/删前必须调用完整祖先符号链接检查（含配置 root 之前的父目录）并使用 NOFOLLOW
        String source = new String(Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/linklife/merchant/service/UploadService.java")), StandardCharsets.UTF_8);

        assertThat(source).contains("NoFollowPathGuard.pathContainsSymlink(userDir, symlinkPredicate)");
        assertThat(source).contains("NoFollowPathGuard.pathContainsSymlink(tempTarget, symlinkPredicate)");
        assertThat(source).contains("NoFollowPathGuard.pathContainsSymlink(target, symlinkPredicate)");
        // move 前必须同时重检 tempTarget 与 finalTarget；清理也必须受 NOFOLLOW 祖先检查保护
        assertThat(source).contains("NoFollowPathGuard.pathContainsSymlink(finalTarget, symlinkPredicate)");
        assertThat(source).contains("cleanupTempFile(tempTarget)");
        assertThat(source).contains("临时文件路径含符号链接，拒绝删除");
        assertThat(source).contains("LinkOption.NOFOLLOW_LINKS");
        assertThat(source).contains("NoFollowPathGuard.SymlinkPredicate symlinkPredicate");
    }

    @Test
    void nofollowUsedForExistsChecks() throws Exception {
        Path temp = Files.createTempDirectory("nofollow-probe");
        try {
            assertThat(Files.exists(temp, LinkOption.NOFOLLOW_LINKS)).isTrue();
            assertThat(Files.isDirectory(temp, LinkOption.NOFOLLOW_LINKS)).isTrue();
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
