package com.linklife.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linklife.social.entity.Blog;
import com.linklife.social.entity.BlogLike;
import com.linklife.social.mapper.BlogMapper;
import com.linklife.social.mapper.BlogLikeMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 博客点赞切换事务服务（017J-D）：以 tb_blog_like 明细为事实，
 * 同一事务内修改明细与 tb_blog.liked 计数；任何一步 affected 异常整体回滚。
 */
@Service
public class BlogLikeTransactionalService {

    @Resource
    private BlogLikeMapper blogLikeMapper;

    @Resource
    private BlogMapper blogMapper;

    /**
     * 切换点赞：返回切换后的点赞状态。
     *
     * @param blogId 博客 id
     * @param userId 当前用户 id
     * @return true=已点赞；false=未点赞
     */
    @Transactional
    public boolean toggleLike(long blogId, long userId) {
        BlogLike existing = blogLikeMapper.selectOne(new LambdaQueryWrapper<BlogLike>()
                .eq(BlogLike::getBlogId, blogId)
                .eq(BlogLike::getUserId, userId));
        if (existing == null) {
            // 未点赞 → 插入明细 + liked+1；两步 affected 必须符合预期，否则回滚
            BlogLike like = new BlogLike();
            like.setBlogId(blogId);
            like.setUserId(userId);
            int inserted = blogLikeMapper.insert(like);
            if (inserted != 1) {
                throw new IllegalStateException("博客点赞明细插入失败（affected=" + inserted + "）");
            }
            int updated = blogMapper.update(null,
                    new LambdaUpdateWrapper<Blog>()
                            .eq(Blog::getId, blogId)
                            .setSql("liked = liked + 1"));
            if (updated != 1) {
                throw new IllegalStateException("博客点赞计数增加失败（affected=" + updated + "），博客可能不存在");
            }
            return true;
        }
        // 已点赞 → 删除明细 + liked-1（liked>0 防下溢）；两步 affected 必须符合预期，否则回滚
        int deleted = blogLikeMapper.delete(new LambdaQueryWrapper<BlogLike>()
                .eq(BlogLike::getBlogId, blogId)
                .eq(BlogLike::getUserId, userId));
        if (deleted != 1) {
            throw new IllegalStateException("博客点赞明细删除失败（affected=" + deleted + "）");
        }
        int updated = blogMapper.update(null,
                new LambdaUpdateWrapper<Blog>()
                        .eq(Blog::getId, blogId)
                        .gt(Blog::getLiked, 0)
                        .setSql("liked = liked - 1"));
        if (updated != 1) {
            throw new IllegalStateException("博客点赞计数减少失败（affected=" + updated + "），博客可能不存在或计数下溢");
        }
        return false;
    }
}
