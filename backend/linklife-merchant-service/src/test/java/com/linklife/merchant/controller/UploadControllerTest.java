package com.linklife.merchant.controller;

import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.common.web.exception.GlobalExceptionHandler;
import com.linklife.merchant.service.UploadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上传接口 HTTP 语义测试（Stage 4）：无用户上下文 → 服务 fail-closed（请先登录）；
 * 有 UserContext → 调用 UploadService 并返回 URL；GET /upload/** 不作为文件读取地址。
 */
class UploadControllerTest {

    private MockMvc mockMvc;
    private UploadService uploadService;

    @BeforeEach
    void setUp() {
        uploadService = mock(UploadService.class);
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "uploadService", uploadService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void anonymousPostUploadFailsClosedWithoutServiceCall() throws Exception {
        when(uploadService.saveImage(any(), isNull()))
                .thenThrow(new BusinessException("请先登录"));
        mockMvc.perform(multipart("/upload/blog")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[10])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("请先登录"));
    }

    @Test
    void anonymousDeleteFailsClosed() throws Exception {
        doThrow(new BusinessException("请先登录"))
                .when(uploadService).deleteImage(any(), isNull());
        mockMvc.perform(delete("/upload/blog").param("name", "/api/files/users/1/x.jpg"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMsg").value("请先登录"));
    }

    @Test
    void getUploadBlogDeleteNotExists() throws Exception {
        mockMvc.perform(get("/upload/blog/delete"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUploadFilenameNotFileReadAddress() throws Exception {
        mockMvc.perform(get("/upload/abc.jpg"))
                .andExpect(status().isNotFound());
    }

    @Test
    void authenticatedUploadCallsServiceAndReturnsUrl() throws Exception {
        when(uploadService.saveImage(any(), eq(1L))).thenReturn("/api/files/users/1/x.jpg");
        UserContext.set(1L);
        try {
            mockMvc.perform(multipart("/upload/blog")
                            .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[10])))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").value("/api/files/users/1/x.jpg"));

            verify(uploadService).saveImage(any(), eq(1L));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void serviceBusinessExceptionPropagates() throws Exception {
        UserContext.set(1L);
        when(uploadService.saveImage(any(), eq(1L)))
                .thenThrow(new BusinessException("上传文件过大"));
        mockMvc.perform(multipart("/upload/blog")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[10])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMsg").value("上传文件过大"));
    }
}
