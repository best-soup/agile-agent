package com.example.agileagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agileagent.entity.ProjectEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectMapper extends BaseMapper<ProjectEntity> {
}
