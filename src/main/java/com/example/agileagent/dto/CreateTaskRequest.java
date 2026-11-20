package com.example.agileagent.dto;

public record CreateTaskRequest(String title, String assigneeName, String description,
                                String priority, String deadline) {
}
