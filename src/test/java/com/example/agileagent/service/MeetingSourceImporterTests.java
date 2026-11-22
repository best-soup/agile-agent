package com.example.agileagent.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MeetingSourceImporterTests {
    private final MeetingSourceImporter importer = new MeetingSourceImporter();

    @Test
    void parsesAllowedFilesWithoutPathsOrTemporaryFiles() throws Exception {
        var text = new MockMultipartFile("file", "../notes.txt", "text/plain", "会议内容测试".getBytes(StandardCharsets.UTF_8));
        assertEquals("notes.txt", importer.file(text).sourceId());
        assertEquals("TXT_FILE", importer.file(text).sourceType());
        assertEquals("MARKDOWN_FILE", importer.file(new MockMultipartFile("file", "notes.md", "text/markdown",
                "# 会议纪要".getBytes(StandardCharsets.UTF_8))).sourceType());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("DOCX 会议内容");
            doc.write(out);
        }
        var docx = new MockMultipartFile("file", "notes.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", out.toByteArray());
        assertTrue(importer.file(docx).content().contains("DOCX 会议内容"));
    }

    @Test
    void rejectsEmptyOversizeUnsupportedAndMalformedFiles() {
        assertThrows(MeetingImportException.class, () -> importer.file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[0])));
        assertThrows(MeetingImportException.class, () -> importer.file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[MeetingSourceImporter.MAX_BYTES + 1])));
        assertThrows(MeetingImportException.class, () -> importer.file(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1})));
        assertThrows(MeetingImportException.class, () -> importer.file(new MockMultipartFile("file", "a.txt", "application/pdf", new byte[]{1})));
        assertThrows(MeetingImportException.class, () -> importer.file(new MockMultipartFile("file", "a.docx", "application/octet-stream", new byte[]{1})));
        assertThrows(MeetingImportException.class, () -> importer.file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{(byte) 0xff})));
    }
}
