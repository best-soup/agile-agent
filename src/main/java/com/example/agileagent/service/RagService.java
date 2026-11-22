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
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agileagent.config.ProjectScope;
import com.example.agileagent.entity.RagDocumentEntity;
import com.example.agileagent.mapper.RagDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final String DEFAULT_EMBEDDING_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private final OpenAiEmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RagDocumentMapper documentMapper;
    private final ProjectAccessService projectAccessService;
    private final String embeddingHost;

    @Autowired
    public RagService(OpenAiEmbeddingModel embeddingModel,
                      @Value("${qdrant.enabled:false}") boolean qdrantEnabled,
                      @Value("${qdrant.host:localhost}") String qdrantHost,
                      @Value("${qdrant.port:6334}") int qdrantPort,
                      @Value("${qdrant.collection-name:agile-agent-docs}") String collectionName,
                      RagDocumentMapper documentMapper,
                      ProjectAccessService projectAccessService,
                      @Value("${langchain4j.open-ai.embedding-model.base-url:" + DEFAULT_EMBEDDING_BASE_URL + "}") String embeddingBaseUrl) {
        this.embeddingModel = embeddingModel;
        this.documentMapper = documentMapper;
        this.projectAccessService = projectAccessService;
        this.embeddingHost = hostOf(embeddingBaseUrl);

        if (qdrantEnabled) {
            this.embeddingStore = CompatibleQdrantEmbeddingStore.compatibleBuilder()
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

    /** Compatibility constructor used by lightweight unit tests. */
    public RagService(OpenAiEmbeddingModel embeddingModel,
                      boolean qdrantEnabled, String qdrantHost, int qdrantPort,
                      String collectionName, RagDocumentMapper documentMapper,
                      ProjectAccessService projectAccessService) {
        this(embeddingModel, qdrantEnabled, qdrantHost, qdrantPort, collectionName,
                documentMapper, projectAccessService, DEFAULT_EMBEDDING_BASE_URL);
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
        String sourceType = source != null && source.startsWith("api:") ? "MEETING" : "DOCUMENT";
        String sourceId = source == null || source.isBlank() ? (title == null ? "inline" : title) : source;
        indexDocument(projectId, content, sourceType, sourceId, title, date, source);
    }

    /** Indexes one source with a stable identity and deterministic chunk IDs. */
    @Transactional(noRollbackFor = RuntimeException.class)
    public void indexDocument(Long projectId, String content, String sourceType, String sourceId,
                              String title, String meetingDate, String sourceUrl) {
        projectAccessService.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        ProjectScope.requireProjectId(projectId);
        if (content == null || content.isBlank()) return;

        // 步骤 1：清洗
        String cleaned = cleanContent(projectId, content);
        if (cleaned.isBlank()) {
            System.out.println("[RAG] 清洗后无有效内容，跳过 projectId=" + projectId);
            return;
        }

        String normalizedSourceType = normalizeSourcePart(sourceType, "DOCUMENT");
        String normalizedSourceId = normalizeSourcePart(sourceId, "inline");
        String contentHash = sha256(cleaned);
        RagDocumentEntity document = findLatestDocument(projectId, normalizedSourceType, normalizedSourceId);
        if (document != null && contentHash.equals(document.getContentHash()) && "READY".equals(document.getStatus())) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (document == null || !contentHash.equals(document.getContentHash())) {
            int version = nextVersion(projectId, normalizedSourceType, normalizedSourceId);
            document = RagDocumentEntity.builder()
                    .documentId(stableDocumentId(projectId, normalizedSourceType, normalizedSourceId))
                    .projectId(projectId).sourceType(normalizedSourceType).sourceId(normalizedSourceId)
                    .title(title).contentHash(contentHash).version(version).status("INDEXING")
                    .createdAt(now).updatedAt(now).build();
            documentMapper.insert(document);
        } else {
            document.setTitle(title);
            document.setContentHash(contentHash);
            document.setStatus("INDEXING");
            document.setFailureReason(null);
            document.setUpdatedAt(now);
            documentMapper.updateById(document);
        }

        int segmentCount;
        try {
            // 删除同一稳定文档的旧 chunks，避免内容更新留下孤儿向量。
            embeddingStore.removeAll(CompatibleQdrantEmbeddingStore.documentFilter(projectId, document.getDocumentId()));

            // 步骤 2：分块（每个 chunk 都携带可追溯 metadata）
            List<TextSegment> segments = splitByParagraph(projectId, cleaned, document,
                    title, meetingDate, sourceUrl);
            if (segments.isEmpty()) {
                throw new IllegalStateException("文档清洗后没有可索引的文本片段");
            }
            segmentCount = segments.size();

            // 步骤 3：向量化 + 确定性 point ID upsert
            embedAllBatched(document, segments);
            document.setStatus("READY");
            document.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(document);
        } catch (RuntimeException exception) {
            try {
                embeddingStore.removeAll(CompatibleQdrantEmbeddingStore.documentFilter(projectId, document.getDocumentId()));
            } catch (RuntimeException ignored) {
                // Cleanup is best effort; status update below must still be attempted.
            }
            try {
                document.setStatus("FAILED");
                document.setFailureReason(safeFailure(exception));
                document.setUpdatedAt(LocalDateTime.now());
                documentMapper.updateById(document);
            } catch (RuntimeException ignored) {
                // Preserve the original indexing error if failure bookkeeping also fails.
            }
            throw exception;
        }

        System.out.println("[RAG] 已索引 " + (title != null ? title : "未命名文档")
                + " | documentId=" + document.getDocumentId() + " | projectId=" + projectId + " | 块数=" + segmentCount
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
                "FILE", fileName, fileName.replaceFirst("\\.[^.]+$", ""), null, fileName);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 检索
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 语义检索，返回携带完整元数据的结果列表。
     */
    public List<SearchResult> search(Long projectId, String query, int maxResults) {
        return search(projectId, query, maxResults, null, null);
    }

    /** Optional metadata filters are combined with the mandatory trusted project filter. */
    public List<SearchResult> search(Long projectId, String query, int maxResults,
                                     String sourceType, String meetingDate) {
        projectAccessService.requireCurrentProject(projectId, ProjectAccessService.Permission.READ);
        Long trustedProjectId = ProjectScope.requireProjectId(projectId);
        Embedding queryEmbedding = embedQuery(query);
        Filter filter = CompatibleQdrantEmbeddingStore.projectFilter(trustedProjectId);
        if (sourceType != null && !sourceType.isBlank()) {
            filter = filter.and(MetadataFilterBuilder.metadataKey("sourceType").isEqualTo(sourceType));
        }
        if (meetingDate != null && !meetingDate.isBlank()) {
            filter = filter.and(MetadataFilterBuilder.metadataKey("meetingDate").isEqualTo(meetingDate));
        }
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(maxResults)
                        .filter(filter)
                        .build());

        return result.matches().stream()
                .filter(m -> m.score() >= 0.7)
                .filter(m -> trustedProjectId.toString().equals(metadataString(m.embedded().metadata(), "projectId")))
                .map(m -> SearchResult.from(m.embedded().text(), m.score(), m.embedded().metadata()))
                .collect(Collectors.toList());
    }

    /**
     * 检索结果：文本片段 + 相似度 + 来源元数据。
     */
    public record SearchResult(String text, double score, String documentId, String projectId,
                                String sourceType, String sourceId, String title, Integer chunkIndex,
                                String meetingDate, String sourceUrl, String contentHash, Integer version,
                                String citationLabel) {
        static SearchResult from(String text, Double score, Metadata meta) {
            String documentId = metadataString(meta, "documentId");
            String projectId = metadataString(meta, "projectId");
            String sourceType = metadataString(meta, "sourceType");
            String sourceId = metadataString(meta, "sourceId");
            String title = metadataString(meta, "title");
            Integer chunkIndex = metadataInteger(meta, "chunkIndex");
            String meetingDate = firstMetadata(meta, "meetingDate", "date");
            String sourceUrl = firstMetadata(meta, "sourceUrl", "source");
            String contentHash = metadataString(meta, "contentHash");
            Integer version = metadataInteger(meta, "version");
            return new SearchResult(
                    text,
                    score != null ? score : 0.0,
                    documentId, projectId, sourceType, sourceId, title, chunkIndex,
                    meetingDate, sourceUrl, contentHash, version,
                    RagService.citationLabel(sourceType, title, meetingDate, documentId, chunkIndex)
            );
        }

        /** Backward-compatible accessors for existing callers. */
        public String date() { return meetingDate; }
        public String source() { return sourceUrl; }

        /** 格式化为 Agent 可读的字符串 */
        public String toAgentFormat() {
            StringBuilder sb = new StringBuilder();
            sb.append("[来源] ").append(citationLabel).append("\n")
                    .append("[文档ID] ").append(documentId == null ? "未知" : documentId).append("\n")
                    .append("[片段] ").append(chunkIndex == null ? "未知" : chunkIndex).append("\n")
                    .append("[内容] ").append(text);
            return sb.toString();
        }
    }

    /** Removes one document and all of its chunks within the trusted project. */
    @Transactional(noRollbackFor = RuntimeException.class)
    public void deleteDocument(Long projectId, String documentId) {
        projectAccessService.requireCurrentProject(projectId, ProjectAccessService.Permission.WRITE);
        Long trustedProjectId = ProjectScope.requireProjectId(projectId);
        embeddingStore.removeAll(CompatibleQdrantEmbeddingStore.documentFilter(trustedProjectId, documentId));
        documentMapper.delete(new LambdaQueryWrapper<RagDocumentEntity>()
                .eq(RagDocumentEntity::getProjectId, trustedProjectId)
                .eq(RagDocumentEntity::getDocumentId, documentId));
    }

    private static final int MAX_CHARS_PER_SEGMENT = 800; // ≈ 512 token（中文 ~1.5 字/token）
    private static final int OVERLAP_CHARS = 100;          // 块间重叠，保上下文连贯

    private List<TextSegment> splitByParagraph(Long projectId, String content, RagDocumentEntity document,
                                               String title, String meetingDate, String sourceUrl) {
        String[] paragraphs = content.split("\\n\\s*\\n");
        List<TextSegment> segments = new ArrayList<>();
        String overlap = "";
        int chunkIndex = 0;

        for (String p : paragraphs) {
            p = p.trim();
            if (p.isEmpty()) continue;

            List<String> chunks = splitLongText(p, MAX_CHARS_PER_SEGMENT, OVERLAP_CHARS);
            for (String chunk : chunks) {
                String text = overlap.isEmpty() ? chunk : overlap + chunk;
                Metadata meta = Metadata.from("projectId", projectId.toString())
                        .put("documentId", document.getDocumentId())
                        .put("sourceType", document.getSourceType())
                        .put("sourceId", document.getSourceId())
                        .put("contentHash", document.getContentHash())
                        .put("version", document.getVersion())
                        .put("chunkIndex", chunkIndex++);
                if (title != null) meta.put("title", title);
                if (meetingDate != null) {
                    meta.put("meetingDate", meetingDate);
                    meta.put("date", meetingDate);
                }
                if (sourceUrl != null) {
                    meta.put("sourceUrl", sourceUrl);
                    meta.put("source", sourceUrl);
                }
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
    private void embedAllBatched(RagDocumentEntity document, List<TextSegment> segments) {
        int batchSize = 8;
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            List<Embedding> embs = embeddingModel.embedAll(batch).content();
            List<String> ids = new ArrayList<>();
            for (int j = i; j < end; j++) {
                ids.add(UUID.nameUUIDFromBytes((document.getDocumentId() + ":" + document.getVersion()
                        + ":" + j).getBytes(StandardCharsets.UTF_8)).toString());
            }
            embeddingStore.addAll(ids, embs, batch);
        }
    }

    private Embedding embedQuery(String query) {
        long started = System.nanoTime();
        try {
            Embedding embedding = embeddingModel.embed(query).content();
            log.debug("RAG downstream success service=DashScopeEmbedding host={} operation=embed "
                            + "elapsedMs={} requestId={}", embeddingHost, elapsedMs(started),
                    ProjectScope.currentRequestId());
            return embedding;
        } catch (RuntimeException exception) {
            log.error("RAG downstream failure service=DashScopeEmbedding host={} operation=embed "
                            + "elapsedMs={} requestId={} exception={} message={}",
                    embeddingHost, elapsedMs(started), ProjectScope.currentRequestId(),
                    exception.getClass().getSimpleName(), safeMessage(exception));
            throw exception;
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "unknown";
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (IllegalArgumentException ignored) {
            return "unknown";
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) return "";
        String oneLine = message.replaceAll("[\\r\\n]+", " ");
        return oneLine.length() > 240 ? oneLine.substring(0, 240) : oneLine;
    }

    private RagDocumentEntity findLatestDocument(Long projectId, String sourceType, String sourceId) {
        return documentMapper.selectList(new LambdaQueryWrapper<RagDocumentEntity>()
                .eq(RagDocumentEntity::getProjectId, projectId)
                .eq(RagDocumentEntity::getSourceType, sourceType)
                .eq(RagDocumentEntity::getSourceId, sourceId)
                .orderByDesc(RagDocumentEntity::getVersion))
                .stream().findFirst().orElse(null);
    }

    private int nextVersion(Long projectId, String sourceType, String sourceId) {
        return documentMapper.selectList(new LambdaQueryWrapper<RagDocumentEntity>()
                        .eq(RagDocumentEntity::getProjectId, projectId)
                        .eq(RagDocumentEntity::getSourceType, sourceType)
                        .eq(RagDocumentEntity::getSourceId, sourceId)
                        .orderByDesc(RagDocumentEntity::getVersion))
                .stream().findFirst().map(d -> d.getVersion() + 1).orElse(1);
    }

    private static String stableDocumentId(Long projectId, String sourceType, String sourceId) {
        return "doc-" + sha256(projectId + "|" + sourceType + "|" + sourceId).substring(0, 32);
    }

    private static String normalizeSourcePart(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return fallback;
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private static String safeFailure(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate document content hash", exception);
        }
    }

    private static String metadataString(Metadata metadata, String key) {
        Object value = metadata.toMap().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer metadataInteger(Metadata metadata, String key) {
        Object value = metadata.toMap().get(key);
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try { return Integer.valueOf(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; }
    }

    private static String firstMetadata(Metadata metadata, String first, String fallback) {
        String value = metadataString(metadata, first);
        return value == null ? metadataString(metadata, fallback) : value;
    }

    private static String citationLabel(String sourceType, String title, String date,
                                        String documentId, Integer chunkIndex) {
        String label = sourceType == null ? "未知来源" : sourceType;
        if (title != null && !title.isBlank()) label += " " + title;
        if (date != null && !date.isBlank()) label += " " + date;
        if (documentId == null) label += " 文档未知";
        if (chunkIndex != null) label += " 第 " + chunkIndex + " 段";
        return label;
    }

    private static String tail(String text, int chars) {
        if (text.length() <= chars) return text + " ";
        return text.substring(text.length() - chars) + " ";
    }
}
