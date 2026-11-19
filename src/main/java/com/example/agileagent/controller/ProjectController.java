package com.example.agileagent.controller;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.dto.CreateProjectRequest;
import com.example.agileagent.dto.ProjectMemberRequest;
import com.example.agileagent.entity.ProjectEntity;
import com.example.agileagent.entity.ProjectMemberEntity;
import com.example.agileagent.mapper.ProjectMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agileagent.service.ProjectAccessService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectAccessService accessService;
    private final ProjectMemberMapper memberMapper;

    public ProjectController(ProjectAccessService accessService, ProjectMemberMapper memberMapper) {
        this.accessService = accessService;
        this.memberMapper = memberMapper;
    }

    @PostMapping
    public ProjectEntity create(@RequestBody CreateProjectRequest request) {
        Long userId = ProjectScope.requireUserId();
        return accessService.createProject(userId, request.name(), request.description());
    }

    @GetMapping
    public List<ProjectEntity> list() {
        Long userId = ProjectScope.requireUserId();
        accessService.requireUser(userId);
        return accessService.listProjects(userId);
    }

    @PostMapping("/{projectId}/members")
    public ProjectMemberEntity addMember(@PathVariable Long projectId,
                                         @RequestBody ProjectMemberRequest request) {
        if (request == null || request.userId() == null) {
            throw new IllegalArgumentException("成员用户 ID 不能为空");
        }
        return ProjectScope.runInProject(projectId,
                () -> accessService.addMember(ProjectScope.requireUserId(), projectId, request.userId(), request.role()));
    }

    @GetMapping("/{projectId}/members")
    public List<ProjectMemberEntity> listMembers(@PathVariable Long projectId) {
        return ProjectScope.runInProject(projectId, () -> {
            accessService.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
            return memberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                    .eq(ProjectMemberEntity::getProjectId, projectId));
        });
    }

    @DeleteMapping("/{projectId}/members/{memberUserId}")
    public void removeMember(@PathVariable Long projectId,
                              @PathVariable Long memberUserId) {
        ProjectScope.runInProject(projectId,
                () -> accessService.removeMember(ProjectScope.requireUserId(), projectId, memberUserId));
    }
}
