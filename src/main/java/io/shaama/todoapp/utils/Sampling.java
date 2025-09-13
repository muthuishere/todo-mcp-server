package io.shaama.todoapp.utils;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.util.Assert;

import java.util.List;

@Slf4j
@UtilityClass
public class Sampling {

    public static String createSamplingRequest(ToolContext ctx, String systemPrompt, String content) {
        // Validate input parameters
        Assert.notNull(ctx, "ToolContext must not be null");
        Assert.notNull(systemPrompt, "System prompt must not be null");
        Assert.notNull(content, "Content must not be null");

        String output = "";
        var mcpExchange = McpToolUtils.getMcpExchange(ctx);

        log.info("Creating Sampling Request");
        if (mcpExchange.isPresent()) {
            McpSyncServerExchange exchange = mcpExchange.get();
            logSamplingStart(exchange);
            if (isSamplingCapabilityAvailable(exchange)) {
                output = performSampling(exchange, systemPrompt, content);
            }
        }
        return output;
    }

    private static void logSamplingStart(McpSyncServerExchange exchange) {
        exchange.loggingNotification(McpSchema.LoggingMessageNotification.builder()
                .level(McpSchema.LoggingLevel.INFO)
                .data("Start sampling")
                .build());
    }

    private static boolean isSamplingCapabilityAvailable(McpSyncServerExchange exchange) {
        return exchange.getClientCapabilities().sampling() != null;
    }

    private static String performSampling(McpSyncServerExchange exchange, String systemPrompt, String content) {
        var request = McpSchema.CreateMessageRequest.builder()
                .systemPrompt(systemPrompt)
                .messages(List.of(
                        new McpSchema.SamplingMessage(
                                McpSchema.Role.USER,
                                new McpSchema.TextContent(content))))
                .build();

        McpSchema.CreateMessageResult result = exchange.createMessage(request);
        return ((McpSchema.TextContent) result.content()).text();
    }
}
