package com.linklife.identity.service.impl;

import com.linklife.identity.entity.UserInfo;
import com.linklife.identity.mapper.UserInfoMapper;
import com.linklife.identity.service.IUserInfoService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
