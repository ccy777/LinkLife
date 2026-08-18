package com.linklife.social.service.impl;

import com.linklife.social.entity.BlogComments;
import com.linklife.social.mapper.BlogCommentsMapper;
import com.linklife.social.service.IBlogCommentsService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
