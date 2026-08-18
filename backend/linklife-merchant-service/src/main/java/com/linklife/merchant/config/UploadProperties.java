package com.linklife.merchant.config;

import cn.hutool.core.util.StrUtil;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 上传配置：
 * - resource-prefix：后端 Spring MVC 实际接收并映射的内部路径（默认 /files/）；
 * - public-prefix：返回给浏览器、写入博客图片字段、传回删除接口的公开 URL（默认 /api/files/）；
 * 两个前缀均支持环境变量覆盖，并采用严格 URL Path segment 契约校验。
 */
@Component
@ConfigurationProperties(prefix = "linklife.upload")
public class UploadProperties {

    private String root = "./data/uploads";
    private String resourcePrefix = "/files/";
    private String publicPrefix = "/api/files/";
    private long maxSizeBytes = 5L * 1024 * 1024;

    /**
     * 严格 URL Path 前缀：/ 开头和结尾，每个 segment 只允许 ASCII 字母/数字/-/./_/~，不允许空 segment。
     */
    private static final Pattern URL_PATH_PREFIX =
            Pattern.compile("^/[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*/$");

    @PostConstruct
    public void validate() {
        if (StrUtil.isBlank(root)) {
            throw new IllegalStateException("linklife.upload.root 不能为空");
        }
        if (maxSizeBytes <= 0L) {
            throw new IllegalStateException("linklife.upload.max-size-bytes 必须大于 0");
        }
        normalizedResourcePrefix();
        normalizedPublicPrefix();
    }

    /**
     * 规范化上传根目录为绝对路径。
     */
    public Path normalizedRootPath() {
        if (StrUtil.isBlank(root)) {
            throw new IllegalStateException("linklife.upload.root 未配置");
        }
        return Paths.get(root).toAbsolutePath().normalize();
    }

    /**
     * 规范化内部资源前缀，并校验不得位于 /upload/ 下。
     */
    public String normalizedResourcePrefix() {
        validateSinglePrefix(resourcePrefix, "resource-prefix");
        if (resourcePrefix.equals("/upload/") || resourcePrefix.startsWith("/upload/")) {
            throw new IllegalStateException("resource-prefix 不得位于 /upload/ 下");
        }
        return resourcePrefix;
    }

    /**
     * 规范化公开 URL 前缀，并要求尾部 segment 与 resource-prefix 完全一致。
     */
    public String normalizedPublicPrefix() {
        validateSinglePrefix(publicPrefix, "public-prefix");
        if (!publicPrefix.endsWith(normalizedResourcePrefix())) {
            throw new IllegalStateException("public-prefix 尾部必须与 resource-prefix 一致");
        }
        return publicPrefix;
    }

    private void validateSinglePrefix(String prefix, String name) {
        if (StrUtil.isBlank(prefix)) {
            throw new IllegalStateException(name + " 不能为空");
        }
        if (!prefix.startsWith("/") || !prefix.endsWith("/")) {
            throw new IllegalStateException(name + " 必须以 / 开头和结尾");
        }
        if ("/".equals(prefix)) {
            throw new IllegalStateException(name + " 不允许为根路径 /");
        }
        if (!URL_PATH_PREFIX.matcher(prefix).matches()) {
            throw new IllegalStateException(name + " 不合法");
        }
        for (String segment : prefix.substring(1, prefix.length() - 1).split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalStateException(name + " 不允许 . 或 .. segment");
            }
        }
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public String getResourcePrefix() {
        return resourcePrefix;
    }

    public void setResourcePrefix(String resourcePrefix) {
        this.resourcePrefix = resourcePrefix;
    }

    public String getPublicPrefix() {
        return publicPrefix;
    }

    public void setPublicPrefix(String publicPrefix) {
        this.publicPrefix = publicPrefix;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }
}
