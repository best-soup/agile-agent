package com.example.agileagent.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data                  // 自动生成 Getter, Setter, toString 等方法
@Builder               // 开启建造者模式 (方便后面链式对象创建：TaskIssueEntity.builder().title("..").build())
@NoArgsConstructor    // 自动生成无参构造函数
@AllArgsConstructor   // 自动生成全参构造函数
@TableName("task_issue") // 告诉 MyBatis-Plus，这个实体类对应 MySQL 里的哪张表
public class TaskIssueEntity {

        @TableId(type = IdType.AUTO) // 声明为主键，且是自增 ID
        private Long id;

        private Long projectId;

        private Long taskLogId; // 关联的 AI 任务流水 ID

        private String title;

        private String assigneeName; // 负责人姓名

        private String description;

        private String priority;

        private LocalDate deadline; // 截止日期，用 Java 8 的 LocalDate 对应 MySQL 的 date 类型

        private Integer isCompleted; // 是否完成：0否，1是
    }

