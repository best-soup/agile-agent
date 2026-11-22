package com.example.agileagent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class QdrantFilterCheck {
    @Container static final GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:v1.13.6").withExposedPorts(6333);
    private final HttpClient client = HttpClient.newHttpClient();

    private String request(String method, String path, String body) throws Exception {
        URI uri = URI.create("http://" + qdrant.getHost() + ":" + qdrant.getMappedPort(6333) + path);
        HttpRequest request = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    @Test void filtersByProjectInsideQdrant() throws Exception {
        request("PUT", "/collections/filter_test", "{\"vectors\":{\"size\":2,\"distance\":\"Cosine\"}}");
        request("PUT", "/collections/filter_test/points?wait=true", "{\"points\":[{\"id\":1,\"vector\":[1,0],\"payload\":{\"projectId\":1}},{\"id\":2,\"vector\":[1,0],\"payload\":{\"projectId\":2}}]}");
        String result = request("POST", "/collections/filter_test/points/search", "{\"vector\":[1,0],\"limit\":10,\"filter\":{\"must\":[{\"key\":\"projectId\",\"match\":{\"value\":1}}]}}");
        var hits = new ObjectMapper().readTree(result).get("result");
        assertEquals(1, hits.size());
        assertEquals(1, hits.get(0).get("id").asInt());
    }
}
