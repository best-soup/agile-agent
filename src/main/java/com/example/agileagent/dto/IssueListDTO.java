package com.example.agileagent.dto;

import lombok.Data;

import java.util.List;

@Data
public class IssueListDTO {
    // 用一个明确的对象把 List 包裹起来，彻底解决泛型擦除问题
    private List<TaskIssueDTO> issues;
}
