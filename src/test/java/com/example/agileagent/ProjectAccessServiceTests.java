package com.example.agileagent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.entity.ProjectEntity;
import com.example.agileagent.entity.ProjectMemberEntity;
import com.example.agileagent.mapper.ProjectMapper;
import com.example.agileagent.mapper.ProjectMemberMapper;
import com.example.agileagent.mapper.UserMapper;
import com.example.agileagent.service.ProjectAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectAccessServiceTests {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
    private final ProjectAccessService accessService = new ProjectAccessService(
            userMapper, projectMapper, memberMapper);

    @AfterEach
    void clearScope() {
        ProjectScope.clear();
    }

    @Test
    void viewerCanReadButCannotWrite() {
        when(projectMapper.selectById(10L)).thenReturn(ProjectEntity.builder().id(10L).ownerId(1L).build());
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(ProjectMemberEntity.builder().projectId(10L).userId(2L).role("VIEWER").build());
        ProjectScope.authenticate(2L);

        assertDoesNotThrow(() -> ProjectScope.runInProject(10L,
                () -> accessService.requireCurrentProject(10L, ProjectAccessService.Permission.READ)));
        assertThrows(SecurityException.class, () -> ProjectScope.runInProject(10L,
                () -> accessService.requireCurrentProject(10L, ProjectAccessService.Permission.WRITE)));
    }

    @Test
    void nonMemberCannotReadProject() {
        when(projectMapper.selectById(10L)).thenReturn(ProjectEntity.builder().id(10L).ownerId(1L).build());
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        ProjectScope.authenticate(3L);

        assertThrows(SecurityException.class, () -> ProjectScope.runInProject(10L,
                () -> accessService.requireCurrentProject(10L, ProjectAccessService.Permission.READ)));
    }

    @Test
    void modelCannotSwitchAwayFromRequestProject() {
        ProjectScope.authenticate(2L);
        ProjectScope.runInProject(10L, () -> assertThrows(SecurityException.class,
                () -> ProjectScope.requireProjectId(11L)));
    }
}
