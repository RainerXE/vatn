package dev.vatn.api;

/**
 * Interface for security filtering and output corridors.
 * Ensures model outputs and tool calls adhere to safety policies.
 */
@VatnApi(since = "1.0")
public interface VGuardService extends VService {
    
    /**
     * Enum defining the decision of the guard.
     */
    enum GuardDecision {
        ALLOW,
        BLOCK,
        PENDING_APPROVAL,
        SANDBOXED
    }

    /**
     * Checks and sanitizes model output.
     * 
     * @param text The raw output from the LLM.
     * @return The sanitized version of the text.
     */
    String checkOutput(String text);

    /**
     * Checks and sanitizes input context before it reaches the model.
     * Used for secret detection and PII redaction (DCN-96).
     * 
     * @param sessionId The current session ID.
     * @param text The raw input text/context.
     * @return The sanitized version of the text, or null if execution should be blocked.
     */
    String sanitizeInput(String sessionId, String text);

    /**
     * Evaluates whether a tool call should be allowed.
     * 
     * @param agentId The ID of the agent making the call.
     * @param toolId The ID of the tool being called.
     * @param args The arguments for the tool call.
     * @return The guard's decision.
     */
    GuardDecision evaluateToolCall(String agentId, String toolId, String args);
}
