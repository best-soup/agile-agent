package com.example.agileagent.service;

import com.google.common.util.concurrent.ListenableFuture;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.CosineSimilarity;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.RelevanceScore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Compatibility wrapper for the beta3 Qdrant client.
 *
 * <p>The parent class keeps the existing upsert/delete/payload behavior. Only
 * the search result conversion is copied here so the newer dense.data field
 * can be read while retaining the old data field fallback.</p>
 */
public class CompatibleQdrantEmbeddingStore extends QdrantEmbeddingStore {

    private final QdrantClient client;
    private final String collectionName;
    private final String payloadTextKey;

    public CompatibleQdrantEmbeddingStore(String collectionName, String host, int port,
                                          boolean useTls, String payloadTextKey, String apiKey) {
        this(createClient(host, port, useTls, apiKey), collectionName,
                payloadTextKey == null ? "text_segment" : payloadTextKey);
    }

    public CompatibleQdrantEmbeddingStore(QdrantClient client, String collectionName,
                                          String payloadTextKey) {
        super(Objects.requireNonNull(client, "client"),
                Objects.requireNonNull(collectionName, "collectionName"),
                payloadTextKey == null ? "text_segment" : payloadTextKey);
        this.client = client;
        this.collectionName = collectionName;
        this.payloadTextKey = payloadTextKey == null ? "text_segment" : payloadTextKey;
    }

    public static Builder compatibleBuilder() {
        return new Builder();
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Points.SearchPoints.Builder builder = Points.SearchPoints.newBuilder()
                .setCollectionName(collectionName)
                .addAllVector(request.queryEmbedding().vectorAsList())
                .setWithVectors(io.qdrant.client.WithVectorsSelectorFactory.enable(true))
                .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true))
                .setLimit(request.maxResults());

        Filter filter = request.filter();
        if (filter != null) {
            builder.setFilter(convertFilter(filter));
        }

        List<Points.ScoredPoint> points;
        try {
            ListenableFuture<List<Points.ScoredPoint>> future = client.searchAsync(builder.build());
            points = future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Qdrant search was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new RuntimeException("Qdrant search failed", exception.getCause());
        }

        if (points.isEmpty()) {
            return new EmbeddingSearchResult<>(Collections.emptyList());
        }

        List<EmbeddingMatch<TextSegment>> matches = points.stream()
                .map(point -> toEmbeddingMatch(point, request.queryEmbedding()))
                .filter(match -> match.score() >= request.minScore())
                .sorted(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score))
                .collect(Collectors.toList());
        Collections.reverse(matches);
        return new EmbeddingSearchResult<>(matches);
    }

    /**
     * Extracts the vector returned by Qdrant. New responses put dense values
     * under dense.data; beta3's old format put them directly under data.
     */
    static List<Float> extractVectorValues(Points.ScoredPoint scoredPoint) {
        if (!scoredPoint.hasVectors() || !scoredPoint.getVectors().hasVector()) {
            throw new IllegalStateException("Qdrant search result has no unnamed vector");
        }

        Points.VectorOutput vector = scoredPoint.getVectors().getVector();
        List<Float> values;
        if (vector.hasDense()) {
            values = vector.getDense().getDataList();
        } else if (vector.hasSparse()) {
            throw new UnsupportedOperationException(
                    "Qdrant sparse vectors are not supported by this embedding store");
        } else if (vector.hasMultiDense()) {
            throw new UnsupportedOperationException(
                    "Qdrant multi-dense vectors are not supported by this embedding store");
        } else {
            values = vector.getDataList();
        }

        if (values == null || values.isEmpty()) {
            throw new IllegalStateException(
                    "Qdrant search result contains an empty vector (dense.data/data)");
        }
        return values;
    }

    private EmbeddingMatch<TextSegment> toEmbeddingMatch(Points.ScoredPoint point,
                                                           Embedding queryEmbedding) {
        Map<String, JsonWithInt.Value> payload = point.getPayloadMap();
        JsonWithInt.Value textValue = payload.get(payloadTextKey);
        Map<String, Object> metadataMap = payload.entrySet().stream()
                .filter(entry -> !payloadTextKey.equals(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> toJavaValue(entry.getValue())));

        List<Float> values = extractVectorValues(point);
        Embedding embedding = Embedding.from(values);
        double cosineSimilarity = CosineSimilarity.between(embedding, queryEmbedding);
        TextSegment segment = textValue == null ? null
                : TextSegment.from(textValue.getStringValue(), new Metadata(metadataMap));
        String id = point.hasId() ? point.getId().getUuid() : null;
        return new EmbeddingMatch<>(RelevanceScore.fromCosineSimilarity(cosineSimilarity),
                id, embedding, segment);
    }

    private static Object toJavaValue(JsonWithInt.Value value) {
        return switch (value.getKindCase()) {
            case INTEGER_VALUE -> value.getIntegerValue();
            case STRING_VALUE -> value.getStringValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                    .map(CompatibleQdrantEmbeddingStore::toJavaValue)
                    .collect(Collectors.toList());
            case STRUCT_VALUE -> value.getStructValue().getFieldsMap().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            entry -> toJavaValue(entry.getValue())));
            case NULL_VALUE -> null;
            case KIND_NOT_SET -> throw new IllegalArgumentException(
                    "Unsupported Qdrant payload value kind: KIND_NOT_SET");
        };
    }

    private static Points.Filter convertFilter(Filter filter) {
        try {
            Class<?> converter = Class.forName(
                    "dev.langchain4j.store.embedding.qdrant.QdrantFilterConverter");
            var method = converter.getDeclaredMethod("convertExpression", Filter.class);
            method.setAccessible(true);
            return (Points.Filter) method.invoke(null, filter);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to convert LangChain4j Qdrant filter", exception);
        }
    }

    private static QdrantClient createClient(String host, int port, boolean useTls, String apiKey) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, useTls);
        if (apiKey != null) {
            builder.withApiKey(apiKey);
        }
        return new QdrantClient(builder.build());
    }

    public static class Builder {
        private String collectionName;
        private String host = "localhost";
        private int port = 6334;
        private boolean useTls;
        private String payloadTextKey = "text_segment";
        private String apiKey;
        private QdrantClient client;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder useTls(boolean useTls) {
            this.useTls = useTls;
            return this;
        }

        public Builder payloadTextKey(String payloadTextKey) {
            this.payloadTextKey = payloadTextKey;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder client(QdrantClient client) {
            this.client = client;
            return this;
        }

        public CompatibleQdrantEmbeddingStore build() {
            if (collectionName == null) {
                throw new NullPointerException("collectionName cannot be null");
            }
            QdrantClient resolvedClient = client == null
                    ? createClient(host, port, useTls, apiKey)
                    : client;
            return new CompatibleQdrantEmbeddingStore(resolvedClient, collectionName, payloadTextKey);
        }
    }
}
