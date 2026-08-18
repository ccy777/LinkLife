package com.linklife.common.web.exception;

import com.linklife.common.core.api.Result;
import com.linklife.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 最小统一异常处理（Stage 3 语义迁移）：参数/业务异常返回稳定 4xx 业务结果；
 * 未知异常返回统一 500，不泄漏堆栈、数据库、Redis 地址、绝对路径或 Token。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误", e);
        return ResponseEntity.badRequest().body(Result.fail("请求参数错误"));
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            BindException.class,
            MethodArgumentNotValidException.class
    })
    public ResponseEntity<Result> handleRequestBinding(Exception e) {
        log.warn("请求参数绑定错误", e);
        return ResponseEntity.badRequest().body(Result.fail("请求参数错误"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleBusiness(BusinessException e) {
        log.warn("业务失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制", e);
        return ResponseEntity.badRequest().body(Result.fail("上传文件过大"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result> handleNoResource(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail("资源不存在"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result> handleNoHandler(NoHandlerFoundException e) {
        log.warn("无匹配处理器: {} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail("资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleUnknown(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("系统繁忙，请稍后再试"));
    }
}
