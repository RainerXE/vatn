package dev.vatn.api;

import java.util.Map;

/**
 * Standard event schema for agentic token streaming.
 * Includes offset for durable session resumption.
 */
@VatnApi(since = "1.0")
public record VStreamEvent(
    String sessionId,
    long offset,
    EventType type,
    String content,
    Map<String, Object> metadata
) {
    public enum EventType {
        TOKEN,
        TOOL_CALL,
        TOOL_RESULT,
        STATUS,
        ERROR,
        AGENT_RESPONSE
    }

    public static VStreamEvent token(String sessionId, String content, long offset) {
        return new VStreamEvent(sessionId, offset, EventType.TOKEN, content, null);
    }

    public static VStreamEvent status(String sessionId, String content) {
        return new VStreamEvent(sessionId, 0, EventType.STATUS, content, null);
    }
}
