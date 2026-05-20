package com.example.agileagent.dto;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class TaskIssueDTO {
    private Long id;

    private Long projectId;

    private Long taskLogId; // 关联的 AI 任务流水 ID

    private String title;

    private String assigneeName; // 负责人姓名

    private String description;

    private String priority;

    private String deadline; // 截止日期，前端传字符串格式的日期

    private Integer isCompleted; // 是否完成：0否，1是

    // 省略 Getter 和 Setter 方法，可以使用 Lombok 注解 @Data 来自动生成
}
