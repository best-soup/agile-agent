package com.example.agileagent.dto;

public record UpdateTaskDraftItemRequest(String title, String assigneeName, String description,
                                         String priority, String deadline, Integer sortOrder) {}
