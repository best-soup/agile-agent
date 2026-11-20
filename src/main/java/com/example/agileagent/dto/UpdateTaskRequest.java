package com.example.agileagent.dto;

public record UpdateTaskRequest(String title, String assigneeName, String description,
                                String priority, String deadline) {
}
