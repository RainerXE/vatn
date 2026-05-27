package dev.vatn.api;

import dev.vatn.api.security.VTrustLevel;

/**
 * Universal execution wrapper for VATN plugins.
 * Supports Java, WASM, Python, and Node.js components as identical "Execution Transactions."
 */
@VatnApi(since = "1.0")
public interface VRuntime extends VService {
    
    /**
     * Type of runtime (e.g., "java", "wasm", "node", "python").
     */
    String getType();
    
    /**
     * Executes a specific entry point or function within the runtime.
     * 
     * @param entryPoint The name of the function/method.
     * @param input Data passed to the transaction.
     * @return Execution result.
     */
    Object execute(String entryPoint, Object input);

    /**
     * Returns the current trust level enforced on this runtime instance.
     */
    VTrustLevel getEnforcedTrustLevel();
}
