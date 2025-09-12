package io.shaama.todoapp.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled("Integration test - run manually when needed")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.main.allow-bean-definition-overriding=true",
    "spring.profiles.active=test,stdio"
})

public class McpServerStdioE2ETest {
    static Logger log = LoggerFactory.getLogger(McpServerStdioE2ETest.class);

    private static BlockingQueue<String> outputQueue;
    private static PrintWriter systemInWriter;
    private static BufferedReader systemOutReader;
    private static final int TIMEOUT_SECONDS = 15;
    private static volatile boolean running = true;
    private static PipedOutputStream pos;
    private static PipedInputStream pis;
    private static PipedInputStream outPis;
    private static PipedOutputStream outPos;
    private static InputStream originalIn;
    private static PrintStream originalOut;
    private static Thread readThread;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @BeforeAll
    public static void setup() throws IOException {
        outputQueue = new LinkedBlockingQueue<>();

        // Save original streams
        originalIn = System.in;
        originalOut = System.out;

        // Set up pipe for System.in
        pos = new PipedOutputStream();
        pis = new PipedInputStream(pos);
        System.setIn(pis);
        systemInWriter = new PrintWriter(new OutputStreamWriter(pos), true);

        // Set up pipe for System.out
        outPis = new PipedInputStream();
        outPos = new PipedOutputStream(outPis);
        System.setOut(new PrintStream(outPos, true));
        systemOutReader = new BufferedReader(new InputStreamReader(outPis));

        // Start reading thread
        readThread = startOutputReader();
    }

    @AfterAll
    public static void cleanup() throws IOException {
        running = false;
        if (readThread != null) {
            readThread.interrupt();
            try {
                readThread.join(5000);
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for reader thread to stop", e);
                Thread.currentThread().interrupt();
            }
        }

        // Clean up streams
        if (systemInWriter != null) systemInWriter.close();
        if (systemOutReader != null) systemOutReader.close();
        if (pos != null) pos.close();
        if (pis != null) pis.close();
        if (outPis != null) outPis.close();
        if (outPos != null) outPos.close();

        // Restore original streams
        System.setIn(originalIn);
        System.setOut(originalOut);
    }

    private static Thread startOutputReader() {
        Thread readThread = new Thread(() -> {
            try {
                String line;
                while (running && (line = systemOutReader.readLine()) != null) {
                    log.info("Stdio received: " + line);
                    outputQueue.add(line);
                }
            } catch (IOException e) {
                if (running) {
                    log.error("Error reading from System.out", e);
                }
            }
        });
        readThread.setDaemon(true);
        readThread.start();
        return readThread;
    }

    @Test
    public void testFullMcpServerStdioFlow() throws Exception {
        // Clear any startup messages
        Thread.sleep(2000);
        outputQueue.clear();

        // Send initialize request
        sendToStdio(createInitializeRequest());
        List<String> initializeResponse = readOutputWithTimeout(3);
        assertTrue(initializeResponse.stream()
            .anyMatch(line -> line.contains("protocolVersion")),
            "Response should contain protocolVersion");

        // Send initialized notification
        sendToStdio(createInitializedNotification());
        readOutputWithTimeout(2); // Wait for any response to initialized notification

        // Send tools/list request to see available tools
        sendToStdio(createToolsListRequest());
        List<String> toolsListResponse = readOutputWithTimeout(5);
        assertTrue(toolsListResponse.stream()
            .anyMatch(line -> line.contains("fetchAllTodos")),
            "Response should contain todo tool");

        // Send tool call request for movie info
        sendToStdio(createTodoToolCallRequest());
        List<String> toolCallResponse = readOutputWithTimeout(9);
        assertTrue(toolCallResponse.stream()
            .anyMatch(line -> line.contains("result") || line.contains("error")),
            "Response should contain result or error");
    }

    private void sendToStdio(String request) {
        log.info("Sending to stdio: " + request);
        systemInWriter.write(request + "\n");  // Explicitly add newline
        systemInWriter.flush();  // Ensure immediate flush
    }

    private List<String> readOutputWithTimeout(int timeoutSeconds) throws InterruptedException {
        List<String> collectedOutput = new ArrayList<>();
        long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        long pollTimeoutMillis = 100;

        log.info("Starting collection for " + timeoutSeconds + " seconds...");

        while (System.currentTimeMillis() < endTime) {
            String output = outputQueue.poll(pollTimeoutMillis, TimeUnit.MILLISECONDS);
            if (output != null) {
                collectedOutput.add(output);
            }
        }

        outputQueue.drainTo(collectedOutput);
        log.info("Collected output:\n" + String.join("\n", collectedOutput));
        return collectedOutput;
    }

    private String createInitializeRequest() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"Visual Studio Code\",\"version\":\"1.99.2\"}}}";
    }

    private String createInitializedNotification() {
        return "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}";
    }

    private String createToolsListRequest() {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";
    }

    private String createTodoToolCallRequest() {
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"fetchAllTodos\",\"arguments\":{}}}";
    }
}