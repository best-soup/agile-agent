package com.example.agileagent.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agileagent.entity.TaskIssueEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper // 告诉 Spring Boot 这是一个 MyBatis 的 Mapper 接口
public interface TaskIssueMapper extends BaseMapper<TaskIssueEntity> {
    // 继承了 BaseMapper 之后，这里什么都不用写！
    // 你的项目已经自动具备了 insert, deleteById, updateById, selectList 等基础方法。
}
