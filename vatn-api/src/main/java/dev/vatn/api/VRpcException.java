package dev.vatn.api;

/**
 * Thrown when an RPC call cannot complete: timeout, transport failure, or no handler registered
 * for the method at the target node.
 */
@VatnApi(since = "1.2")
public class VRpcException extends Exception {

    public VRpcException(String message) {
        super(message);
    }

    public VRpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
