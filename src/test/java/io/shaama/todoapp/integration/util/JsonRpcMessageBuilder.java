package io.shaama.todoapp.integration.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility class for generating JSON-RPC messages for MCP testing
 */
public class JsonRpcMessageBuilder {
    
    private final AtomicInteger idCounter = new AtomicInteger(1);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Generates the next sequential ID for requests
     */
    private int nextId() {
        return idCounter.getAndIncrement();
    }
    
    /**
     * Reset ID counter (useful for tests)
     */
    public void resetIdCounter() {
        idCounter.set(1);
    }
    
    /**
     * Creates an MCP initialize request
     */
    public String createInitializeRequest() {
        return String.format("""
                {
                    "jsonrpc": "2.0",
                    "id": %d,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": "2025-03-26",
                        "capabilities": {
                            "roots": {
                                "listChanged": true
                            }
                        },
                        "clientInfo": {
                            "name": "Visual Studio Code",
                            "version": "1.99.2"
                        }
                    }
                }""", nextId()).replaceAll("\\s+", " ").trim();
    }
    
    /**
     * Creates an MCP initialized notification
     */
    public String createInitializedNotification() {
        return """
                {
                    "method": "notifications/initialized",
                    "jsonrpc": "2.0"
                }""".replaceAll("\\s+", " ").trim();
    }
    
    /**
     * Creates a tools/list request
     */
    public String createToolsListRequest() {
        return String.format("""
                {
                    "jsonrpc": "2.0",
                    "id": %d,
                    "method": "tools/list",
                    "params": {}
                }""", nextId()).replaceAll("\\s+", " ").trim();
    }
    
    /**
     * Creates a generic tool call request
     */
    public String createToolCallRequest(String toolName, Map<String, Object> arguments) {
        try {
            String argumentsJson = objectMapper.writeValueAsString(arguments);
            return String.format("""
                    {
                        "jsonrpc": "2.0",
                        "id": %d,
                        "method": "tools/call",
                        "params": {
                            "name": "%s",
                            "arguments": %s
                        }
                    }""", nextId(), toolName, argumentsJson).replaceAll("\\s+", " ").trim();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize arguments", e);
        }
    }
    
    /**
     * Creates a movie info tool call request (backward compatibility)
     */
    public String createTodoToolCallRequest(String movieName) {
        return createToolCallRequest("fetchAllTodos", Map.of());
    }
}