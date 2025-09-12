package io.shaama.todoapp.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.shaama.todoapp.integration.util.JsonRpcMessageBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.main.allow-bean-definition-overriding=true",
    "spring.profiles.active=test,streamable"
})
@Disabled
public class McpServerStreamableE2ETest {

    private static final Logger log = LoggerFactory.getLogger(McpServerStreamableE2ETest.class);

    @LocalServerPort
    private int port;

    private JsonRpcMessageBuilder jsonRpcBuilder;
    private HttpClient httpClient;
    private String baseUrl;
    private String sessionId;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    public void setupTest() {
        jsonRpcBuilder = new JsonRpcMessageBuilder();
        jsonRpcBuilder.resetIdCounter();

        // Create HTTP client with keep-alive support
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        baseUrl = "http://localhost:" + port + "/mcp";
        sessionId = null;
    }

    @Test
    public void testStreamableHttpProtocol() throws Exception {

        // Step 1: Initialize with HTTP POST expecting JSON response
        log.info("=== Step 1: Initialize via HTTP POST ===");
        String initRequest = jsonRpcBuilder.createInitializeRequest();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(initRequest))
            .build();

        HttpResponse<String> initResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.info("Initialize response status: {}", initResponse.statusCode());
        log.info("Initialize response headers: {}", initResponse.headers().map());
        log.info("Initialize response body: {}", initResponse.body());

        assertEquals(200, initResponse.statusCode());
        assertTrue(initResponse.body().contains("protocolVersion"));

        // Extract session ID if present
        sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (sessionId != null) {
            log.info("Session ID received: {}", sessionId);
        }

        // Step 2: Send initialized notification (should return 202 Accepted)
        log.info("=== Step 2: Send initialized notification ===");
        String initializedNotification = jsonRpcBuilder.createInitializedNotification();

        HttpRequest.Builder notificationBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(initializedNotification));

        if (sessionId != null) {
            notificationBuilder.header("Mcp-Session-Id", sessionId);
        }

        HttpResponse<String> notificationResponse = httpClient.send(
            notificationBuilder.build(),
            HttpResponse.BodyHandlers.ofString()
        );

        log.info("Notification response status: {}", notificationResponse.statusCode());
        assertEquals(202, notificationResponse.statusCode()); // 202 Accepted for notifications

        // Step 3: Test tools/list with potential streaming response
        log.info("=== Step 3: Test tools/list request ===");
        String toolsListRequest = jsonRpcBuilder.createToolsListRequest();

        HttpRequest.Builder toolsBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(toolsListRequest));

        if (sessionId != null) {
            toolsBuilder.header("Mcp-Session-Id", sessionId);
        }

        HttpResponse<String> toolsResponse = httpClient.send(
            toolsBuilder.build(),
            HttpResponse.BodyHandlers.ofString()
        );

        log.info("Tools response status: {}", toolsResponse.statusCode());
        log.info("Tools response content-type: {}",
            toolsResponse.headers().firstValue("Content-Type").orElse("none"));
        log.info("Tools response body: {}", toolsResponse.body());

        assertEquals(200, toolsResponse.statusCode());
        assertTrue(toolsResponse.body().contains("fetchAllTodos"));

        // Step 4: Test tool call with potential streaming
        log.info("=== Step 4: Test tool call with streaming capability ===");
        String toolCallRequest = jsonRpcBuilder.createToolCallRequest("fetchAllTodos",
            Map.of());

        HttpRequest.Builder toolCallBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(toolCallRequest));

        if (sessionId != null) {
            toolCallBuilder.header("Mcp-Session-Id", sessionId);
        }

        HttpResponse<String> toolCallResponse = httpClient.send(
            toolCallBuilder.build(),
            HttpResponse.BodyHandlers.ofString()
        );

        log.info("Tool call response status: {}", toolCallResponse.statusCode());
        log.info("Tool call content-type: {}",
            toolCallResponse.headers().firstValue("Content-Type").orElse("none"));
        log.info("Tool call response body: {}", toolCallResponse.body());

        assertEquals(200, toolCallResponse.statusCode());
        assertTrue(toolCallResponse.body().contains("result") || toolCallResponse.body().contains("error"));

    }


}
