package com.example.agileagent.service;

public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException() {
        super("当前项目中未找到该工单");
    }
}
