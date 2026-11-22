package com.example.agileagent.controller;

import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.config.RequestIdFilter;
import com.example.agileagent.dto.MeetingRecordResponse;
import com.example.agileagent.service.MeetingImportException;
import com.example.agileagent.service.MeetingRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MeetingRecordControllerTests {
    private final MeetingRecordService service = mock(MeetingRecordService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MeetingRecordController(service))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();

    @AfterEach void clear() { ProjectScope.clear(); }

    @Test
    void multipartRoutesIntoTrustedProjectAndRejectsInvalidFileWithRequestId() throws Exception {
        ProjectScope.authenticate(7L, "request-1");
        var file = new MockMultipartFile("file", "meeting.txt", "text/plain", "meeting content".getBytes());
        when(service.importFile(eq(2L), any())).thenAnswer(inv -> {
            org.junit.jupiter.api.Assertions.assertEquals(2L, ProjectScope.currentProjectId());
            return new MeetingRecordResponse(9L, 2L, "TXT_FILE", "meeting.txt", "meeting.txt", null,
                    "hash", null, null, 7L, "READY", null, null, null);
        });
        mvc.perform(multipart("/api/projects/2/meeting-records/files").file(file))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(9));
        when(service.importFile(eq(2L), any())).thenThrow(new MeetingImportException("不支持的文件格式或 MIME 类型"));
        mvc.perform(multipart("/api/projects/2/meeting-records/files").file(file).header("X-Request-Id", "upload-test"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEETING_IMPORT_INVALID"))
                .andExpect(jsonPath("$.requestId").value("upload-test"));
    }
}
