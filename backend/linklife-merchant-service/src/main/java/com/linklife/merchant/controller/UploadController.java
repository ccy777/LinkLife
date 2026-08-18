package com.linklife.merchant.controller;

import com.linklife.common.core.api.Result;
import com.linklife.common.core.context.UserContext;
import com.linklife.merchant.service.UploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @Resource
    private UploadService uploadService;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        // 类型/大小/文件名/路径校验与写入由 UploadService 完成，异常统一由 GlobalExceptionHandler 处理
        Long userId = UserContext.getUserId();
        String url = uploadService.saveImage(image, userId);
        return Result.ok(url);
    }

    @DeleteMapping("blog")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        Long userId = UserContext.getUserId();
        uploadService.deleteImage(filename, userId);
        return Result.ok();
    }
}
