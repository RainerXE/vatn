package dev.vatn.api;

/**
 * The result of an RPC call.
 *
 * @param ok           true if the remote handler completed normally
 * @param payload      the response payload bytes (empty on error)
 * @param errorMessage the remote error description when {@code ok} is false, otherwise null
 */
@VatnApi(since = "1.2")
public record VRpcResponse(boolean ok, byte[] payload, String errorMessage) {

    /** A successful response carrying {@code payload}. */
    public static VRpcResponse ok(byte[] payload) {
        return new VRpcResponse(true, payload == null ? new byte[0] : payload, null);
    }

    /** A failed response carrying an error message. */
    public static VRpcResponse error(String message) {
        return new VRpcResponse(false, new byte[0], message);
    }
}
