package com.example.agileagent.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    public RagService(OpenAiEmbeddingModel embeddingModel,
                      @Value("${qdrant.enabled:false}") boolean qdrantEnabled,
                      @Value("${qdrant.host:localhost}") String qdrantHost,
                      @Value("${qdrant.port:6334}") int qdrantPort,
                      @Value("${qdrant.collection-name:agile-agent-docs}") String collectionName) {
        this.embeddingModel = embeddingModel;

        if (qdrantEnabled) {
            this.embeddingStore = QdrantEmbeddingStore.builder()
                    .host(qdrantHost)
                    .port(qdrantPort)
                    .collectionName(collectionName)
                    .useTls(false)
                    .build();
            System.out.println("[RAG] 向量存储: Qdrant (" + qdrantHost + ":" + qdrantPort
                    + "), collection=" + collectionName);
        } else {
            this.embeddingStore = new InMemoryEmbeddingStore<>();
            System.out.println("[RAG] 向量存储: InMemory（重启后数据丢失，设 qdrant.enabled=true 启用持久化）");
        }
    }

    /**
     * 索引文档（向后兼容，不带元数据）。
     */
    public void indexDocument(Long projectId, String content) {
        indexDocument(projectId, content, null, null, null);
    }

    /**
     * 索引文档，携带来源元数据（标题、日期、来源文件名）。
     *
     * @param title  文档标题 / 会议主题，可为 null
     * @param date   文档日期 YYYY-MM-DD，可为 null
     * @param source 来源文件名或 URL，可为 null
     */
    public void indexDocument(Long projectId, String content,
                              String title, String date, String source) {
        if (content == null || content.isBlank()) return;

        // 步骤 1：清洗
        String cleaned = cleanContent(projectId, content);
        if (cleaned.isBlank()) {
            System.out.println("[RAG] 清洗后无有效内容，跳过 projectId=" + projectId);
            return;
        }

        // 步骤 2：分块（元数据随每个块写入）
        List<TextSegment> segments = splitByParagraph(projectId, cleaned, title, date, source);
        if (segments.isEmpty()) return;

        // 步骤 3：向量化 + 存储
        embedAllBatched(segments);

        System.out.println("[RAG] 已索引 " + (title != null ? title : "未命名文档")
                + " | projectId=" + projectId + " | 块数=" + segments.size()
                + " | 原始=" + content.length() + " 清洗后=" + cleaned.length());
    }

    /**
     * 从文件路径索引文档。自动识别格式（.txt / .docx / .md），
     * 解析后走完整的清洗→分块→向量化管道。
     * 文件名自动作为 source 元数据。
     */
    public void indexDocument(Long projectId, Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        String content = DocumentParser.parse(filePath);
        if (content == null || content.isBlank()) {
            System.out.println("[RAG] 文件无有效内容: " + filePath);
            return;
        }
        indexDocument(projectId, content,
                fileName.replaceFirst("\\.[^.]+$", ""),
                null,
                fileName);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 检索
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 语义检索，返回携带完整元数据的结果列表。
     */
    public List<SearchResult> search(Long projectId, String query, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(maxResults)
                        .build());

        return result.matches().stream()
                .filter(m -> m.score() >= 0.7)
                .filter(m -> {
                    String sid = m.embedded().metadata().getString("projectId");
                    return sid != null && sid.equals(projectId.toString());
                })
                .map(m -> SearchResult.from(m.embedded().text(), m.score(), m.embedded().metadata()))
                .collect(Collectors.toList());
    }

    /**
     * 检索结果：文本片段 + 相似度 + 来源元数据。
     */
    public record SearchResult(String text, double score,
                                String title, String date, String source) {
        static SearchResult from(String text, Double score, Metadata meta) {
            return new SearchResult(
                    text,
                    score != null ? score : 0.0,
                    meta.getString("title"),
                    meta.getString("date"),
                    meta.getString("source")
            );
        }

        /** 格式化为 Agent 可读的字符串 */
        public String toAgentFormat() {
            StringBuilder sb = new StringBuilder();
            if (title != null) sb.append("【").append(title).append("】");
            if (date != null) sb.append(" 日期:").append(date);
            if (source != null) sb.append(" 来源:").append(source);
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(text);
            return sb.toString();
        }
    }

    private static final int MAX_CHARS_PER_SEGMENT = 800; // ≈ 512 token（中文 ~1.5 字/token）
    private static final int OVERLAP_CHARS = 100;          // 块间重叠，保上下文连贯

    private List<TextSegment> splitByParagraph(Long projectId, String content,
                                               String title, String date, String source) {
        String[] paragraphs = content.split("\\n\\s*\\n");
        List<TextSegment> segments = new ArrayList<>();
        Metadata meta = Metadata.from("projectId", projectId.toString());
        if (title != null) meta.put("title", title);
        if (date != null) meta.put("date", date);
        if (source != null) meta.put("source", source);
        String overlap = "";

        for (String p : paragraphs) {
            p = p.trim();
            if (p.isEmpty()) continue;

            List<String> chunks = splitLongText(p, MAX_CHARS_PER_SEGMENT, OVERLAP_CHARS);
            for (String chunk : chunks) {
                String text = overlap.isEmpty() ? chunk : overlap + chunk;
                segments.add(TextSegment.from(text, meta));
                overlap = tail(chunk, OVERLAP_CHARS);
            }
        }
        return segments;
    }

    private List<String> splitLongText(String text, int maxChars, int overlapChars) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= maxChars) {
            chunks.add(text);
            return chunks;
        }
        String[] sentences = text.split("(?<=[。！？.?!])");
        StringBuilder chunk = new StringBuilder();
        for (String s : sentences) {
            if (chunk.length() + s.length() > maxChars && !chunk.isEmpty()) {
                chunks.add(chunk.toString().trim());
                String prev = chunk.toString();
                chunk = new StringBuilder();
                if (prev.length() > overlapChars) {
                    chunk.append(prev.substring(prev.length() - overlapChars));
                }
            }
            chunk.append(s);
        }
        if (!chunk.isEmpty()) chunks.add(chunk.toString().trim());
        return chunks;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 清洗管道：去除干扰信号，保留语义信息
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 对原始文档执行多步清洗，去除嵌入向量的噪声。
     *
     * <h3>清洗步骤</h3>
     * <ol>
     *   <li>去除控制字符和零宽字符</li>
     *   <li>去除时间戳模式（中文括号/英文括号/年月日/ISO 格式）</li>
     *   <li>去除纯发言人标记行（"张三："、"李四（PM）："）</li>
     *   <li>全角数字字母转半角，统一标点</li>
     *   <li>合并连续空白行，保留段落边界</li>
     *   <li>过滤过短的无意义行（< 8 字符）</li>
     * </ol>
     */
    private String cleanContent(Long projectId, String raw) {
        String text = raw;

        // ① 去除控制字符（保留换行和制表符，后续统一处理）
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        // 去除零宽字符
        text = text.replaceAll("[\\u200B-\\u200F\\uFEFF]", "");

        // ② 去除时间戳模式
        // ISO 格式：2026-03-15 或 2026-03-15 14:30:00
        text = text.replaceAll("\\d{4}-\\d{2}-\\d{2}(\\s+\\d{2}:\\d{2}(:\\d{2})?)?", "");
        // 中文括号日期：（2026年3月15日）或（2026-03-15）
        text = text.replaceAll("[（(]\\s*\\d{4}[/年-]\\d{1,2}[/月-]\\d{1,2}\\s*日?\\s*[）)]", "");
        // 纯中文日期：2026年3月15日
        text = text.replaceAll("\\d{4}年\\d{1,2}月\\d{1,2}日", "");

        // ③ 去除纯发言人标记行
        // 匹配 "姓名：" 或 "姓名（角色）：" 或 "姓名(角色):" 开头的行
        text = text.replaceAll(
                "(?m)^[\\u4e00-\\u9fa5\\w]{2,6}[：:](\\s*$)?", "");

        // ④ 全角转半角（数字和英文字母，提升 Embedding 一致性）
        text = fullWidthToHalfWidth(text);

        // ⑤ 合并连续空白行（>2 个换行 → 2 个换行，保留段落语义）
        text = text.replaceAll("\\n{3,}", "\n\n");
        // 空格/Tab 统一为单个空格
        text = text.replaceAll("[ \\t]+", " ");
        // 行首行尾空白
        text = text.lines().map(String::strip)
                .collect(java.util.stream.Collectors.joining("\n"));

        // ⑥ 过滤过短的无意义行（< 8 字符，如页码、分隔符等）
        text = text.lines()
                .filter(line -> line.replaceAll("[\\s\\p{Punct}]", "").length() >= 8)
                .collect(java.util.stream.Collectors.joining("\n"));

        // 去首尾空白
        text = text.trim();

        return text;
    }

    /**
     * 全角数字/字母/符号 → 半角，统一编码。
     * 全角标点（，。！？）保留，不转换——它们在中文语义中有意义。
     */
    private static String fullWidthToHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 全角数字 ０-９ → 0-9
            if (c >= '０' && c <= '９') {
                sb.append((char) (c - '０' + '0'));
            }
            // 全角大写字母 Ａ-Ｚ → A-Z
            else if (c >= 'Ａ' && c <= 'Ｚ') {
                sb.append((char) (c - 'Ａ' + 'A'));
            }
            // 全角小写字母 ａ-ｚ → a-z
            else if (c >= 'ａ' && c <= 'ｚ') {
                sb.append((char) (c - 'ａ' + 'a'));
            }
            // 全角空格 → 半角空格
            else if (c == '　') {
                sb.append(' ');
            }
            else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 分批 Embedding，千问 v3 上限 10、v1/v2 上限 25，统一用 8 留余量 */
    private void embedAllBatched(List<TextSegment> segments) {
        int batchSize = 8;
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            List<Embedding> embs = embeddingModel.embedAll(batch).content();
            embeddingStore.addAll(embs, batch);
        }
    }

    private static String tail(String text, int chars) {
        if (text.length() <= chars) return text + " ";
        return text.substring(text.length() - chars) + " ";
    }
}
