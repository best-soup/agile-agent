package com.example.agileagent.service;

import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompatibleQdrantEmbeddingStoreTest {

    @Test
    void denseDataIsPreferredOverLegacyData() {
        Points.VectorOutput vector = Points.VectorOutput.newBuilder()
                .addAllData(List.of(99.0f))
                .setDense(Points.DenseVector.newBuilder()
                        .addAllData(List.of(1.0f, 2.0f, 3.0f)))
                .build();
        Points.ScoredPoint point = scoredPoint(vector);

        assertEquals(List.of(1.0f, 2.0f, 3.0f),
                CompatibleQdrantEmbeddingStore.extractVectorValues(point));
    }

    @Test
    void legacyDataIsUsedWhenDenseFieldIsAbsent() {
        Points.VectorOutput vector = Points.VectorOutput.newBuilder()
                .addAllData(List.of(1.0f, 2.0f, 3.0f))
                .build();

        assertEquals(List.of(1.0f, 2.0f, 3.0f),
                CompatibleQdrantEmbeddingStore.extractVectorValues(scoredPoint(vector)));
    }

    @Test
    void emptyVectorFailsBeforeCosineSimilarity() {
        Points.VectorOutput vector = Points.VectorOutput.newBuilder().build();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> CompatibleQdrantEmbeddingStore.extractVectorValues(scoredPoint(vector)));
        assertEquals("Qdrant search result contains an empty vector (dense.data/data)",
                exception.getMessage());
    }

    private static Points.ScoredPoint scoredPoint(Points.VectorOutput vector) {
        return Points.ScoredPoint.newBuilder()
                .setVectors(Points.VectorsOutput.newBuilder().setVector(vector))
                .build();
    }
}
