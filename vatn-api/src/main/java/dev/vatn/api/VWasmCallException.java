package dev.vatn.api;

/**
 * Thrown when a WebAssembly function call fails — either because the function
 * does not exist, the module traps (unreachable, out-of-bounds, division by zero),
 * or a WASI process exits with a non-zero status code.
 */
@VatnApi(since = "1.0-alpha.11")
public class VWasmCallException extends RuntimeException {

    private final int exitCode;

    public VWasmCallException(String message) {
        super(message);
        this.exitCode = -1;
    }

    public VWasmCallException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public VWasmCallException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = -1;
    }

    /** Non-zero exit code from a WASI process, or -1 for non-WASI traps. */
    public int exitCode() { return exitCode; }
}
