package com.example.agileagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agileagent.entity.ApiKeyEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKeyEntity> {
}
