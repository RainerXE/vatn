package dev.vatn.core.security;

import dev.vatn.api.VGuardService;

/**
 * Default VGuardService shipped with vatn-core.
 *
 * <p>This implementation is intentionally minimal:
 * <ul>
 *   <li>{@link #checkOutput} and {@link #sanitizeInput} are pass-throughs — they return text
 *       unchanged. Override them to add content filtering, PII redaction, etc.</li>
 *   <li>{@link #evaluateToolCall} runs {@link VSsrfGuard} on the tool arguments and blocks calls
 *       that contain private/loopback IPs or cloud-metadata hostnames.</li>
 * </ul>
 *
 * <p>To replace this with your own guard, register a custom implementation in your plugin's
 * {@code onInitialize}:
 * <pre>
 *   context.registerService(VGuardService.class, new MyGuard());
 * </pre>
 * The last registration wins, so plugins loaded after the node starts can override this default.
 *
 * @see VSsrfGuard
 */
public class VGuardServiceImpl implements VGuardService {

    private final VSsrfGuard ssrfGuard = new VSsrfGuard();

    @Override
    public String checkOutput(String text) {
        return text;
    }

    @Override
    public String sanitizeInput(String sessionId, String text) {
        return text;
    }

    @Override
    public GuardDecision evaluateToolCall(String agentId, String toolId, String args) {
        if (args != null && ssrfGuard.isSsrfAttempt(args)) {
            return GuardDecision.BLOCK;
        }
        return GuardDecision.ALLOW;
    }
}
