package com.example.agileagent.service;

public class DraftNotFoundException extends RuntimeException {
    public DraftNotFoundException() { super("当前项目中未找到该草稿或草稿项"); }
}
