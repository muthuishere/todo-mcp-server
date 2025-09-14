package io.shaama.todoapp.todo;

import lombok.Getter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiTestController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "timestamp", LocalDateTime.now().toString(),
            "service", "todo-mcp-server",
            "version", "1.0.0"
        ));
    }

    @GetMapping("/test")
    public ResponseEntity<String> getApiStatus() {
        return ResponseEntity.ok("MCP Todo Server is running!");
    }

    // Add a simple root endpoint for basic connectivity tests
    @GetMapping("/")
    public ResponseEntity<Map<String, String>> getRoot() {
        return ResponseEntity.ok(Map.of(
            "message", "Todo MCP Server API",
            "health", "/api/health",
            "mcp", "/mcp"
        ));
    }
}
