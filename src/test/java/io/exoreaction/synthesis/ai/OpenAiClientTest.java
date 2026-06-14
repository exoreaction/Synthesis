package io.exoreaction.synthesis.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests OpenAiClient against a stub OpenAI-compatible endpoint
 * ({@code com.sun.net.httpserver.HttpServer} on an ephemeral port).
 */
class OpenAiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record StubResponse(int status, String body) {}

    private HttpServer server;
    private OpenAiClient client;
    private final List<JsonNode> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<StubResponse> responses = new ConcurrentLinkedDeque<>();

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBodies.add(MAPPER.readTree(exchange.getRequestBody()));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            StubResponse response = responses.pollFirst();
            StubResponse effective = response != null ? response
                    : new StubResponse(200, completion("ok", "stop"));
            byte[] bytes = effective.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(effective.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort();
        client = new OpenAiClient(endpoint, "deepseek-v4-flash", "test-key");
    }

    @AfterEach
    void stopStubServer() {
        server.stop(0);
    }

    private static String completion(String content, String finishReason) {
        return """
                {"choices":[{"message":{"role":"assistant","content":"%s"},"finish_reason":"%s"}]}
                """.formatted(content, finishReason);
    }

    @Test
    void generateSendsChatCompletionRequestShape() {
        responses.add(new StubResponse(200, completion("Hello from DeepSeek", "stop")));

        String result = client.generate("Describe Synthesis", 256);

        assertEquals("Hello from DeepSeek", result);
        assertEquals("Bearer test-key", authHeaders.get(0));
        JsonNode body = requestBodies.get(0);
        assertEquals("deepseek-v4-flash", body.path("model").asText());
        assertEquals(256, body.path("max_tokens").asInt());
        assertEquals(1, body.path("messages").size());
        assertEquals("user", body.path("messages").path(0).path("role").asText());
        assertEquals("Describe Synthesis", body.path("messages").path(0).path("content").asText());
        assertFalse(body.has("temperature"));
    }

    @Test
    void generateWithMetaDetectsTruncationViaFinishReasonLength() {
        responses.add(new StubResponse(200, completion("Partial...", "length")));

        AiClient.GenerationResult result = client.generateWithMeta("prompt", 16, 0.0);

        assertTrue(result.truncated());
        assertEquals("Partial...", result.content());
        assertEquals(0.0, requestBodies.get(0).path("temperature").asDouble());
    }

    @Test
    void generateWithMetaReportsCompleteOutput() {
        responses.add(new StubResponse(200, completion("Complete.", "stop")));

        AiClient.GenerationResult result = client.generateWithMeta("prompt", 1024, 0.7);

        assertFalse(result.truncated());
        assertEquals("Complete.", result.content());
        assertEquals(0.7, requestBodies.get(0).path("temperature").asDouble());
    }

    @Test
    void serverErrorRaisesWithStatusAndBodySnippetAfterRetry() {
        responses.add(new StubResponse(500, "{\"error\":\"boom\"}"));
        responses.add(new StubResponse(500, "{\"error\":\"boom\"}"));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client.generate("prompt", 64));

        assertTrue(error.getMessage().contains("500"), error.getMessage());
        assertTrue(error.getMessage().contains("boom"), error.getMessage());
        assertEquals(2, requestBodies.size(), "5xx should be retried exactly once");
    }

    @Test
    void rateLimitRetriesOnceThenSucceeds() {
        responses.add(new StubResponse(429, "{\"error\":\"rate limited\"}"));
        responses.add(new StubResponse(200, completion("Recovered", "stop")));

        assertEquals("Recovered", client.generate("prompt", 64));
        assertEquals(2, requestBodies.size());
    }

    @Test
    void clientErrorIsNotRetried() {
        responses.add(new StubResponse(400, "{\"error\":\"bad request\"}"));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client.generate("prompt", 64));

        assertTrue(error.getMessage().contains("400"), error.getMessage());
        assertEquals(1, requestBodies.size());
    }

    @Test
    void generateFromImageSendsOpenAiContentArray(@TempDir Path tempDir) throws IOException {
        Path image = tempDir.resolve("tiny.png");
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "PNG", image.toFile());
        responses.add(new StubResponse(200, completion("A tiny black square", "stop")));

        String result = client.generateFromImage(image, "Describe this image", 128);

        assertEquals("A tiny black square", result);
        JsonNode content = requestBodies.get(0).path("messages").path(0).path("content");
        assertTrue(content.isArray());
        assertEquals("text", content.path(0).path("type").asText());
        assertEquals("Describe this image", content.path(0).path("text").asText());
        assertEquals("image_url", content.path(1).path("type").asText());
        assertTrue(content.path(1).path("image_url").path("url").asText()
                .startsWith("data:image/png;base64,"));
    }

    @Test
    void trailingSlashOnEndpointIsNormalized() {
        assertEquals("http://localhost:1234",
                new OpenAiClient("http://localhost:1234/", "m", "k").getEndpoint());
    }
}
