package dev.vatn.examples.guard;

import dev.vatn.api.VGuardService;
import dev.vatn.core.security.VSsrfGuard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Example VGuardService that combines SSRF protection, PII redaction, and a
 * keyword deny-list. Register it in your plugin's onInitialize to replace the
 * default vatn-core passthrough guard.
 *
 * <p>Covers three layers:
 * <ol>
 *   <li>{@link #sanitizeInput} — redacts PII (emails, phones, cards) before it
 *       reaches the model so secrets don't leak into prompts or logs.</li>
 *   <li>{@link #checkOutput} — strips or blocks model output that contains
 *       dangerous keywords or commands.</li>
 *   <li>{@link #evaluateToolCall} — blocks SSRF URLs and a configurable tool
 *       deny-list before any tool is executed.</li>
 * </ol>
 */
public class ContentFilterGuard implements VGuardService {

    // PII patterns — redact before text reaches the model
    private static final Pattern EMAIL =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE =
            Pattern.compile("\\b(?:\\+?\\d[\\s.\\-]?)?\\(?\\d{3}\\)?[\\s.\\-]?\\d{3}[\\s.\\-]?\\d{4}\\b");
    private static final Pattern CREDIT_CARD =
            Pattern.compile("\\b(?:\\d[ \\-]?){13,16}\\b");

    // Keywords that must never appear in model output
    private static final List<String> OUTPUT_DENY_LIST = List.of(
            "rm -rf", "DROP TABLE", "DROP DATABASE",
            "shutdown", "format c:", "/etc/shadow"
    );

    // Tool IDs that this node refuses to execute under any circumstances
    private static final List<String> TOOL_DENY_LIST = List.of(
            "shell_exec", "eval", "exec_python"
    );

    private final VSsrfGuard ssrfGuard = new VSsrfGuard();

    // -----------------------------------------------------------------------
    // Input sanitization — runs BEFORE the model sees the text
    // -----------------------------------------------------------------------

    @Override
    public String sanitizeInput(String sessionId, String text) {
        if (text == null) return null;
        String result = EMAIL.matcher(text).replaceAll("[EMAIL REDACTED]");
        result = PHONE.matcher(result).replaceAll("[PHONE REDACTED]");
        result = CREDIT_CARD.matcher(result).replaceAll("[CARD REDACTED]");
        return result;
    }

    // -----------------------------------------------------------------------
    // Output check — runs AFTER the model produces text
    // -----------------------------------------------------------------------

    @Override
    public String checkOutput(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase();
        for (String keyword : OUTPUT_DENY_LIST) {
            if (lower.contains(keyword.toLowerCase())) {
                return "[BLOCKED: output contained a disallowed pattern]";
            }
        }
        return text;
    }

    // -----------------------------------------------------------------------
    // Tool call evaluation — runs BEFORE any tool is invoked
    // -----------------------------------------------------------------------

    @Override
    public GuardDecision evaluateToolCall(String agentId, String toolId, String args) {
        // Hard-blocked tools
        if (toolId != null && TOOL_DENY_LIST.contains(toolId)) {
            return GuardDecision.BLOCK;
        }
        // SSRF check on the serialized arguments
        if (args != null && ssrfGuard.isSsrfAttempt(args)) {
            return GuardDecision.BLOCK;
        }
        return GuardDecision.ALLOW;
    }
}
