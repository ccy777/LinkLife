package com.linklife.social.mapper;

import com.linklife.social.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface FollowMapper extends BaseMapper<Follow> {

    /**
     * 查询两个用户的共同关注（MySQL 事实源，不使用 Redis SINTER）。
     * 稳定顺序：按 follow_user_id 升序。
     */
    @Select("SELECT f1.follow_user_id "
            + "FROM tb_follow f1 "
            + "JOIN tb_follow f2 ON f1.follow_user_id = f2.follow_user_id "
            + "AND f2.user_id = #{targetUserId} "
            + "WHERE f1.user_id = #{currentUserId} "
            + "ORDER BY f1.follow_user_id ASC")
    List<Long> selectCommonFollowUserIds(
            @Param("currentUserId") Long currentUserId,
            @Param("targetUserId") Long targetUserId);
}
