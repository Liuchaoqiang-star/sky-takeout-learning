package com.sky.mapper;

import com.sky.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    /**
     * 根据openid查询用户。
     * openid是微信用户在当前小程序下的唯一标识。
     */
    User getByOpenid(String openid);

    /**
     * 新增C端用户。
     * useGeneratedKeys会把数据库自增生成的id回填到user.id，Controller生成token时会用到。
     */
    void insert(User user);

    /**
     * 根据id查询用户。
     */
    @Select("select * from user where id = #{id}")
    User getById(Long id);
}
