package com.linklife.identity.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.common.core.user.UserSummaryDTO;
import com.linklife.common.core.user.UserSummaryRequest;
import com.linklife.common.web.exception.GlobalExceptionHandler;
import com.linklife.identity.entity.User;
import com.linklife.identity.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Identity 内部批量用户摘要 API 语义测试。
 */
class InternalUserControllerTest {

    private InternalUserController controller;
    private IUserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new InternalUserController();
        userService = mock(IUserService.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private User user(long id, String nickName, String icon) {
        User u = new User();
        u.setId(id);
        u.setNickName(nickName);
        u.setIcon(icon);
        return u;
    }

    @Test
    void nullRequestReturnsEmptyWithoutDb() {
        assertThat(controller.batch(null)).isEmpty();
        verify(userService, never()).listByIds(anyCollection());
    }

    @Test
    void emptySetReturnsEmptyWithoutDb() {
        assertThat(controller.batch(new UserSummaryRequest(Set.of()))).isEmpty();
        assertThat(controller.batch(new UserSummaryRequest(null))).isEmpty();
        verify(userService, never()).listByIds(anyCollection());
    }

    @Test
    void invalidIdsRejectedWithoutDb() {
        assertThatThrownBy(() -> controller.batch(new UserSummaryRequest(Set.of(1L, 0L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.batch(new UserSummaryRequest(Set.of(1L, -5L))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userService, never()).listByIds(anyCollection());
    }

    @Test
    void allExistReturnsAllSummariesWithSingleDbCall() {
        when(userService.listByIds(Set.of(1L, 2L)))
                .thenReturn(List.of(user(1L, "a", "ia"), user(2L, "b", "ib")));
        List<UserSummaryDTO> result = controller.batch(new UserSummaryRequest(Set.of(1L, 2L)));
        assertThat(result).hasSize(2);
        assertThat(result).anyMatch(d -> d.id().equals(1L) && "a".equals(d.nickName()) && "ia".equals(d.icon()));
        assertThat(result).anyMatch(d -> d.id().equals(2L) && "b".equals(d.nickName()) && "ib".equals(d.icon()));
        verify(userService, times(1)).listByIds(anyCollection());
    }

    @Test
    void partialMissingReturnsOnlyExisting() {
        when(userService.listByIds(Set.of(1L, 999L))).thenReturn(List.of(user(1L, "a", "ia")));
        List<UserSummaryDTO> result = controller.batch(new UserSummaryRequest(Set.of(1L, 999L)));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
    }

    @Test
    void allMissingReturnsEmpty() {
        when(userService.listByIds(Set.of(777L, 888L))).thenReturn(List.of());
        assertThat(controller.batch(new UserSummaryRequest(Set.of(777L, 888L)))).isEmpty();
    }

    @Test
    void responseJsonContainsOnlyIdNickNameIcon() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new UserSummaryDTO(1L, "nick", "icon"));
        assertThat(json).contains("id").contains("nickName").contains("icon");
        assertThat(json).doesNotContain("phone").doesNotContain("password")
                .doesNotContain("createTime").doesNotContain("updateTime");
    }

    @Test
    void nullIdInJsonBatchRejects400WithoutDbQuery() throws Exception {
        mockMvc.perform(post("/internal/users/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[1,null]}"))
                .andExpect(status().isBadRequest());
        verify(userService, never()).listByIds(anyCollection());
    }

    @Test
    void emptyJsonBatchReturns200EmptyList() throws Exception {
        String body = mockMvc.perform(post("/internal/users/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("[]");
        verify(userService, never()).listByIds(anyCollection());
    }
}
