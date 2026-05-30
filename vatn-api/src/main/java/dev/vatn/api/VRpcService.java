package dev.vatn.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Application-level request/response RPC between VATN nodes, layered over the node's
 * {@link VMessaging} transport (in-process today, OIPC-federated under VATN Lattice v2). Adds
 * correlation, timeouts, and typed error propagation on top of {@code VMessaging}'s fire-and-forget
 * publish/subscribe — without callers touching channels, correlation IDs, or the OIPC wire.
 *
 * <p>Use this to offload work to another node (e.g. a converter pool) or to drive data sync —
 * {@link VReplicationService} is built on top of it.
 *
 * <h3>Server side — expose a method</h3>
 * <pre>{@code
 * VRpcService rpc = ctx.getService(VRpcService.class).orElseThrow();
 * rpc.register("convert.pdf", req -> {
 *     byte[] pdf = converter.toPdf(req.payload());
 *     return pdf;                       // becomes the response payload
 * });
 * }</pre>
 *
 * <h3>Client side — call a remote node</h3>
 * <pre>{@code
 * VRpcResponse resp = rpc.call("node-7", "convert.pdf", docBytes, Duration.ofSeconds(30));
 * if (resp.ok()) handle(resp.payload());
 *
 * // JSON marshalling is the caller's choice, via ctx.getJson():
 * VJson json = ctx.getJson();
 * byte[] arg = json.stringify(request).getBytes(StandardCharsets.UTF_8);
 * VRpcResponse r = rpc.call("node-7", "search.query", arg, Duration.ofSeconds(5));
 * }</pre>
 *
 * <p>Routing is by channel name derived from the target node id, so the same code path works for a
 * loopback call on a single node and for a federated call once {@code VMessaging} spans nodes.
 */
@VatnApi(since = "1.2")
public interface VRpcService extends VService {

    /**
     * Registers a handler for {@code method}. Replaces any existing handler for that method.
     * The handler runs on a virtual thread; its returned bytes become the response payload, and a
     * thrown exception becomes an error response delivered to the caller.
     */
    void register(String method, VRpcHandler handler);

    /** Removes a previously registered handler. No-op if absent. */
    void unregister(String method);

    /**
     * Invokes {@code method} on {@code targetNodeId} and blocks for the response.
     *
     * @param targetNodeId destination node id (use this node's own id for loopback)
     * @param method       the remote method name
     * @param payload      request payload bytes (may be empty)
     * @param timeout      maximum time to wait for a response
     * @return the response; check {@link VRpcResponse#ok()} for success
     * @throws VRpcException on timeout, transport failure, or no handler at the target
     */
    VRpcResponse call(String targetNodeId, String method, byte[] payload, Duration timeout) throws VRpcException;

    /** Asynchronous variant of {@link #call}. The future completes exceptionally with a {@link VRpcException}. */
    CompletableFuture<VRpcResponse> callAsync(String targetNodeId, String method, byte[] payload, Duration timeout);
}
