package com.linklife.identity.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.linklife.common.core.api.Result;
import com.linklife.identity.dto.LoginFormDTO;
import com.linklife.identity.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result logout(String token);

    Result sign();

    Result signCount();

}
