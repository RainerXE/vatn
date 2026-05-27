package dev.vatn.api;

import java.util.Map;

/**
 * Standardized telemetry and UI bridge for VATN nodes.
 * Allows plugins and agents to report progress (tokens, actions, metrics)
 * to any connected interface (CLI, Desktop, Mobile) via a uniform contract.
 */
@VatnApi(since = "1.0")
public interface VUiService extends VService {

    /**
     * Reports a streaming token (e.g. from an LLM).
     */
    void onStream(String sessionId, String token);

    /**
     * Reports a significant action or tool call.
     * @param actionId Unique identifier for the action (e.g. "file_write")
     * @param status START, END, ERROR
     * @param metadata Structured data about the action
     */
    void onAction(String sessionId, String actionId, String status, Map<String, Object> metadata);

    /**
     * Reports numerical metrics updates.
     */
    void onMetrics(String sessionId, Map<String, Object> metrics);

    /**
     * Reports a high-level notification or alert.
     */
    void onNotification(String sessionId, String type, String message);
}
