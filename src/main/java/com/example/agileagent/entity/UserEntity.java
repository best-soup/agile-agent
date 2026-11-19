package com.example.agileagent.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("app_user")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String displayName;
    @JsonIgnore
    private String apiKeyHash;
    private LocalDateTime createdAt;
}
