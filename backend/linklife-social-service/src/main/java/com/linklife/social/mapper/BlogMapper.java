package com.linklife.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linklife.social.entity.Blog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface BlogMapper extends BaseMapper<Blog> {

    /**
     * 关注流查询（017J-D）：MySQL 参数化 JOIN，以 tb_follow + tb_blog 为事实源。
     * 顺序：create_time DESC, id DESC；LIMIT offset,count 保持滚动分页语义。
     */
    @Select("SELECT b.* FROM tb_follow f "
            + "JOIN tb_blog b ON b.user_id = f.follow_user_id "
            + "WHERE f.user_id = #{userId} AND b.create_time <= #{maxTime} "
            + "ORDER BY b.create_time DESC, b.id DESC "
            + "LIMIT #{offset}, #{count}")
    List<Blog> selectFollowFeed(@Param("userId") Long userId,
                                @Param("maxTime") LocalDateTime maxTime,
                                @Param("offset") int offset,
                                @Param("count") int count);
}
