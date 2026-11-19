package com.example.agileagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agileagent.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
