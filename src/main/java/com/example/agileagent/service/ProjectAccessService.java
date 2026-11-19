package com.example.agileagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.entity.ProjectEntity;
import com.example.agileagent.entity.ProjectMemberEntity;
import com.example.agileagent.entity.UserEntity;
import com.example.agileagent.mapper.ProjectMapper;
import com.example.agileagent.mapper.ProjectMemberMapper;
import com.example.agileagent.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProjectAccessService {

    public enum Permission { READ, WRITE, MANAGE_MEMBERS }

    private final UserMapper userMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper memberMapper;

    public ProjectAccessService(UserMapper userMapper,
                                ProjectMapper projectMapper,
                                ProjectMemberMapper memberMapper) {
        this.userMapper = userMapper;
        this.projectMapper = projectMapper;
        this.memberMapper = memberMapper;
    }

    public UserEntity requireUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new SecurityException("用户不存在: " + userId);
        }
        return user;
    }

    public ProjectEntity requireProject(Long projectId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
        return project;
    }

    public ProjectMemberEntity requireAccess(Long userId, Long projectId, Permission permission) {
        ProjectEntity project = requireProject(projectId);
        if (project.getOwnerId() != null && project.getOwnerId().equals(userId)) {
            return ProjectMemberEntity.builder().projectId(projectId).userId(userId).role("OWNER").build();
        }
        ProjectMemberEntity member = memberMapper.selectOne(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getUserId, userId));
        if (member == null || !allows(member.getRole(), permission)) {
            throw new SecurityException("用户 " + userId + " 无权访问项目 " + projectId);
        }
        return member;
    }

    public void requireCurrentProject(Long requestedProjectId, Permission permission) {
        Long userId = ProjectScope.requireUserId();
        Long projectId = ProjectScope.requireProjectId(requestedProjectId);
        requireAccess(userId, projectId, permission);
    }

    public List<ProjectEntity> listProjects(Long userId) {
        List<ProjectEntity> owned = projectMapper.selectList(new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getOwnerId, userId));
        List<ProjectMemberEntity> memberships = memberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getUserId, userId));
        for (ProjectMemberEntity membership : memberships) {
            ProjectEntity project = projectMapper.selectById(membership.getProjectId());
            if (project != null && owned.stream().noneMatch(p -> p.getId().equals(project.getId()))) {
                owned.add(project);
            }
        }
        return owned;
    }

    @Transactional
    public ProjectEntity createProject(Long ownerId, String name, String description) {
        requireUser(ownerId);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        ProjectEntity project = ProjectEntity.builder()
                .name(name.trim())
                .description(description)
                .ownerId(ownerId)
                .createdAt(LocalDateTime.now())
                .build();
        projectMapper.insert(project);
        memberMapper.insert(ProjectMemberEntity.builder()
                .projectId(project.getId())
                .userId(ownerId)
                .role("OWNER")
                .createdAt(LocalDateTime.now())
                .build());
        return project;
    }

    @Transactional
    public ProjectMemberEntity addMember(Long operatorId, Long projectId, Long userId, String role) {
        requireAccess(operatorId, projectId, Permission.MANAGE_MEMBERS);
        requireUser(userId);
        String normalizedRole = normalizeRole(role);
        if ("OWNER".equals(normalizedRole)) {
            throw new IllegalArgumentException("OWNER 角色只能由项目创建者持有");
        }
        ProjectMemberEntity existing = memberMapper.selectOne(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getUserId, userId));
        ProjectMemberEntity member = existing == null
                ? ProjectMemberEntity.builder().projectId(projectId).userId(userId).createdAt(LocalDateTime.now()).build()
                : existing;
        member.setRole(normalizedRole);
        if (existing == null) {
            memberMapper.insert(member);
        } else {
            memberMapper.updateById(member);
        }
        return member;
    }

    @Transactional
    public void removeMember(Long operatorId, Long projectId, Long userId) {
        requireAccess(operatorId, projectId, Permission.MANAGE_MEMBERS);
        ProjectEntity project = requireProject(projectId);
        if (userId.equals(project.getOwnerId())) {
            throw new IllegalArgumentException("不能移除项目所有者");
        }
        memberMapper.delete(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getUserId, userId));
    }

    private boolean allows(String role, Permission permission) {
        String normalized = normalizeRole(role);
        return switch (permission) {
            case READ -> true;
            case WRITE -> !"VIEWER".equals(normalized);
            case MANAGE_MEMBERS -> "ADMIN".equals(normalized);
        };
    }

    private String normalizeRole(String role) {
        String normalized = role == null || role.isBlank() ? "MEMBER" : role.trim().toUpperCase();
        if (!List.of("OWNER", "ADMIN", "MEMBER", "VIEWER").contains(normalized)) {
            throw new IllegalArgumentException("不支持的成员角色: " + role);
        }
        return normalized;
    }
}
