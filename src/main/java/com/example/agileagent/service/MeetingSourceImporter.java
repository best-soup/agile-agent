package com.example.agileagent.service;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Component
public class MeetingSourceImporter {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    public record Imported(String sourceType, String sourceId, String title, String content) { }

    public Imported text(String title, String content, String sourceId) {
        if (content == null || content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new MeetingImportException("会议内容为空或超过 5 MB");
        return new Imported("MANUAL_TEXT", sourceId, title, content);
    }

    public Imported file(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES)
            throw new MeetingImportException("文件为空或超过 5 MB");
        String supplied = file.getOriginalFilename();
        String name = supplied == null ? "" : supplied.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl))
            throw new MeetingImportException("文件名无效");
        String lower = name.toLowerCase(Locale.ROOT);
        String mime = file.getContentType();
        mime = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String type;
        if (lower.endsWith(".txt") && Set.of("text/plain", "application/octet-stream").contains(mime)) type = "TXT_FILE";
        else if (lower.endsWith(".md") && Set.of("text/markdown", "text/plain", "application/octet-stream").contains(mime)) type = "MARKDOWN_FILE";
        else if (lower.endsWith(".docx") && Set.of(DOCX_MIME, "application/octet-stream").contains(mime)) type = "DOCX_FILE";
        else throw new MeetingImportException("不支持的文件格式或 MIME 类型");
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new MeetingImportException("文件为空或超过 5 MB");
            String content = "DOCX_FILE".equals(type)
                    ? DocumentParser.parseDocx(new java.io.ByteArrayInputStream(bytes))
                    : decodeUtf8(bytes);
            if (content.isBlank()) throw new MeetingImportException("文件内容为空");
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
                throw new MeetingImportException("解析后内容超过 5 MB");
            return new Imported(type, name, name, content);
        } catch (MeetingImportException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new MeetingImportException("文件解析失败");
        }
    }

    private String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
