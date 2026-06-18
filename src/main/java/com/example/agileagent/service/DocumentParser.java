package com.example.agileagent.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 多格式文档解析器。
 *
 * 支持格式：纯文本(.txt)、Word(.docx)、原始字符串。
 * 每种格式解析结果一致——返回按段落分隔的纯文本，可直接进入清洗和分块管道。
 *
 * <h3>扩展点</h3>
 * 后续可加 PDF（Apache PDFBox）、Markdown、HTML 等解析器，都实现同样的 String→String 接口即可。
 */
public class DocumentParser {

    /**
     * 从文件路径解析，自动识别格式。
     */
    public static String parse(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".docx")) {
            return parseDocx(filePath);
        }
        if (fileName.endsWith(".txt") || fileName.endsWith(".md")) {
            return parseText(filePath);
        }
        // 未知格式当纯文本尝试
        return parseText(filePath);
    }

    /**
     * 解析 Word .docx 文件，提取所有段落文本，用双换行分隔。
     */
    public static String parseDocx(Path filePath) throws IOException {
        List<String> paragraphs = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             XWPFDocument doc = new XWPFDocument(fis)) {

            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    paragraphs.add(text.trim());
                }
            }

            // 也提取表格中的文本
            doc.getTables().forEach(table ->
                table.getRows().forEach(row ->
                    row.getTableCells().forEach(cell -> {
                        String cellText = cell.getText();
                        if (cellText != null && !cellText.isBlank()) {
                            paragraphs.add(cellText.trim());
                        }
                    })
                )
            );
        }

        return String.join("\n\n", paragraphs);
    }

    /**
     * 解析纯文本文件。
     */
    public static String parseText(Path filePath) throws IOException {
        return Files.readString(filePath);
    }

    /**
     * 原始字符串直接透传（兼容已有调用方式）。
     */
    public static String parseRaw(String content) {
        return content;
    }
}
