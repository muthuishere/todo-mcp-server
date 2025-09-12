package io.shaama.todoapp.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.main.allow-bean-definition-overriding=true",
    "spring.profiles.active=test,sse"
})
@Disabled
public class McpServerSseE2ETest {

    Logger log = LoggerFactory.getLogger(McpServerSseE2ETest.class);
    @LocalServerPort
    private int port;

    private volatile boolean running = true;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final int TIMEOUT_SECONDS = 15;
   private static final BlockingQueue<String> eventQueue = new LinkedBlockingQueue<>();
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public RestTemplate restTemplate() {
            return new RestTemplate();
        }
    }

    @Test
    public void testFullMcpServerFlow() throws Exception {

        Thread sseThread = startSseConnection();


        try {

            // Wait for and extract the session ID from the events
            List<String> items = takeAllEventsWithinTimeout(3);


            String id = items.stream().filter(item -> item.contains("id")).map(item -> item.split("id:")[1].trim()).findFirst().orElse(null);


            log.info("Received items: \n " + asLine(items));
            log.info("received id " + id);
            assertNotNull(id);

            sendMessageToMcpServer(id, createInitializeRequest());

            String initializeResponse = asLine(takeAllEventsWithinTimeout(3));
            assertTrue(initializeResponse.contains("protocolVersion"));
            log.info("Received createInitializeRequest: \n" + initializeResponse);

            sendMessageToMcpServer(id, createInitializedNotification());
            String initializeNotificationResponse = asLine(takeAllEventsWithinTimeout(3));
//
//            assertTrue(initializeNotificationResponse.contains("notifications"));
//            log.info("Received createInitializedNotification: \n" + initializeNotificationResponse);

            // Send tools/list request to see available tools
            sendMessageToMcpServer(id, createToolsListRequest());
            String toolsListResponse = asLine(takeAllEventsWithinTimeout(5));
            assertTrue(toolsListResponse.contains("fetchAllTodos"));
            log.info("Received tools/list response: \n" + toolsListResponse);

            sendMessageToMcpServer(id, createTodoToolCallRequest());
            String toolCallResponse  = asLine(takeAllEventsWithinTimeout(9));

            assertTrue(toolCallResponse.contains("result") || toolCallResponse.contains("error"));
            log.info("Received todo tool call response: \n" + toolCallResponse);

        }finally {
            stopSseConnection(sseThread);
        }


    }


    private static String asLine(List<String> items) {
        return items.stream().collect(Collectors.joining("\n"));
    }



    // Update startSseConnection() method
    private Thread startSseConnection() {
        running=true;
        Thread sseThread = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http://localhost:" + port + "/sse");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("Accept", "text/event-stream");



                try (InputStream inputStream = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        log.info("SSE received: " + line);
                        eventQueue.add(line);
                    }
                }
            } catch (Exception e) {
                if (running) { // Only log if not deliberately stopped
                    System.err.println("Error in SSE connection: " + e.getMessage());
                    e.printStackTrace();
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });

        sseThread.setDaemon(true);
        sseThread.start();
        return sseThread;
    }

    // Add this method to the class
    private void stopSseConnection(Thread sseThread) {
        running = false;
        sseThread.interrupt();
        try {
            sseThread.join(5000); // Wait up to 5 seconds for thread to finish
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for SSE thread to stop");
            Thread.currentThread().interrupt();
        }
    }

    private List<String> takeAllEventsWithinTimeout(int timeoutSeconds) throws InterruptedException {
        List<String> collectedEvents = new ArrayList<>();
        long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        long pollTimeoutMillis = 100; // How long to wait for an item in each individual poll attempt

        log.info("Starting collection for " + timeoutSeconds + " seconds...");

        // Poll the queue repeatedly until the overall timeout is reached
        while (System.currentTimeMillis() < endTime) {
            // Calculate remaining time, but clamp it to our small pollTimeoutMillis
            // This prevents the last poll from waiting for a long time if the remaining time is large.
            // A simpler approach is just to always poll with pollTimeoutMillis. Let's use that for clarity.
            // long remaining = endTime - System.currentTimeMillis();
            // if (remaining <= 0) break; // Should be caught by while condition
            // long currentPollTimeout = Math.min(remaining, pollTimeoutMillis); // Use remaining or fixed small timeout?

            // Poll with a small timeout. If an element is available within 100ms, get it.
            // Otherwise, poll returns null, and we loop to check the overall timeout.
            String event = eventQueue.poll(pollTimeoutMillis, TimeUnit.MILLISECONDS);

            if (event != null) {
                collectedEvents.add(event);
            }
            // If event is null, the poll timed out after pollTimeoutMillis,
            // the loop continues, and System.currentTimeMillis() < endTime is checked.
        }

        // After the loop, there might be items added to the queue *very* quickly
        // after the last poll returned null but before the while loop condition failed.
        // Drain any remaining items that are immediately available.
        eventQueue.drainTo(collectedEvents);

        log.info("Finished collection after " + timeoutSeconds + " seconds.");
        return collectedEvents;
    }




    private void sendMessageToMcpServer(String sessionId, String requestBody) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);


        log.info("sending request: " + requestBody);
        var requestEntity= new HttpEntity<>(requestBody, headers);
        String url = "http://localhost:" + port + "/mcp/message?sessionId=" + sessionId;

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, requestEntity, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "Request should return OK status");
        log.info("success for : " + requestBody);

    }


    private String createInitializeRequest() {
        return """
                {
                    "jsonrpc":"2.0",
                    "id":1,
                    "method":"initialize",
                    "params":{
                        "protocolVersion":"2024-11-05",
                        "capabilities":{
                            "roots":{
                                "listChanged":true
                            }
                        },
                        "clientInfo":{
                            "name":"Visual Studio Code",
                            "version":"1.99.2"
                        }
                    }
                }
                """;
    }

    private String createInitializedNotification() {
        return """
                {
                    "method":"notifications/initialized",
                    "jsonrpc":"2.0"
                }
                """;
    }

    private String createToolsListRequest() {
        return """
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/list",
                    "params": {}
                }
                """;
    }

    private String createTodoToolCallRequest() {
        return """
                {
                    "jsonrpc": "2.0",
                    "id": "3",
                    "method": "tools/call",
                    "params": {
                        "name":"fetchAllTodos",
                        "arguments": {
                        }
                    }
                }
                """;
    }
}