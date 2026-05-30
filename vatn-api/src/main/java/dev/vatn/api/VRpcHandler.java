package dev.vatn.api;

/**
 * Handles inbound RPC requests for a registered method. Runs on a virtual thread.
 *
 * <p>Return the response payload bytes, or throw to signal an error back to the caller (the
 * exception message is propagated as {@link VRpcResponse#errorMessage()}).
 */
@VatnApi(since = "1.2")
@FunctionalInterface
public interface VRpcHandler {

    /**
     * @param request the inbound request
     * @return the response payload bytes (may be empty; null is treated as empty)
     * @throws Exception to return an error response to the caller
     */
    byte[] handle(VRpcRequest request) throws Exception;
}
