package dev.vatn.api;

/**
 * An inbound RPC request delivered to a {@link VRpcHandler}.
 *
 * @param method       the invoked method name
 * @param callerNodeId the node id that issued the call
 * @param payload      the request payload bytes (never null; may be empty)
 */
@VatnApi(since = "1.2")
public record VRpcRequest(String method, String callerNodeId, byte[] payload) {}
