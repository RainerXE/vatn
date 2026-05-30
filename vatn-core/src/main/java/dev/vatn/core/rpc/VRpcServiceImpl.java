package dev.vatn.core.rpc;

import dev.vatn.api.VMessaging;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VRpcException;
import dev.vatn.api.VRpcHandler;
import dev.vatn.api.VRpcRequest;
import dev.vatn.api.VRpcResponse;
import dev.vatn.api.VRpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * RPC over {@link VMessaging}. Adds correlation, timeout, and error propagation on top of the
 * node's publish/subscribe transport (LOCAL in v1, OIPC-federated under Lattice v2).
 *
 * <p>Wire framing is a compact length-prefixed binary envelope so payloads stay binary-safe:
 * {@code [type][correlationId][method|error][callerNode][payloadLen][payload]}. Requests for a node
 * travel on channel {@code vatn.rpc.req.<nodeId>}; responses return on {@code vatn.rpc.resp.<nodeId>}.
 */
public class VRpcServiceImpl implements VRpcService {

    private static final Logger log = LoggerFactory.getLogger(VRpcServiceImpl.class);

    private static final byte TYPE_REQUEST = 1;
    private static final byte TYPE_RESPONSE_OK = 2;
    private static final byte TYPE_RESPONSE_ERR = 3;

    private final VNodeContext ctx;
    private final String localNodeId;
    private final VMessaging messaging;

    private final ConcurrentHashMap<String, VRpcHandler> handlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<VRpcResponse>> pending = new ConcurrentHashMap<>();

    private final Consumer<byte[]> requestListener = this::onRequest;
    private final Consumer<byte[]> responseListener = this::onResponse;

    public VRpcServiceImpl(VNodeContext ctx, VMessaging messaging) {
        this(ctx.getNodeId(), messaging);
    }

    /** Test-friendly constructor that takes a plain node-id string directly. */
    public VRpcServiceImpl(String nodeId, VMessaging messaging) {
        this.ctx = null;
        this.localNodeId = nodeId;
        this.messaging = messaging;
        messaging.subscribe(reqChannel(localNodeId), requestListener);
        messaging.subscribe(respChannel(localNodeId), responseListener);
        log.info("[RPC] Listening on {} for node {}", reqChannel(localNodeId), localNodeId);
    }

    // ── server side ─────────────────────────────────────────────────────────────

    @Override
    public void register(String method, VRpcHandler handler) {
        handlers.put(method, handler);
        log.info("[RPC] Registered handler for method '{}'", method);
    }

    @Override
    public void unregister(String method) {
        handlers.remove(method);
    }

    private void onRequest(byte[] frame) {
        Envelope env;
        try {
            env = decode(frame);
        } catch (IOException e) {
            log.warn("[RPC] Dropping malformed request frame", e);
            return;
        }
        if (env.type != TYPE_REQUEST) return;

        Thread.ofVirtual().name("vatn-rpc-" + env.errorOrMethod).start(() -> {
            VRpcHandler handler = handlers.get(env.errorOrMethod);
            Envelope reply;
            if (handler == null) {
                reply = Envelope.error(env.correlationId, localNodeId, "No handler for method: " + env.errorOrMethod);
            } else {
                try {
                    byte[] result = handler.handle(new VRpcRequest(env.errorOrMethod, env.callerNodeId, env.payload));
                    reply = Envelope.ok(env.correlationId, localNodeId, result != null ? result : new byte[0]);
                } catch (Exception e) {
                    log.debug("[RPC] Handler '{}' threw", env.errorOrMethod, e);
                    reply = Envelope.error(env.correlationId, localNodeId,
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
            try {
                messaging.publish(respChannel(env.callerNodeId), encode(reply));
            } catch (IOException e) {
                log.warn("[RPC] Failed to send response for correlation {}", env.correlationId, e);
            }
        });
    }

    // ── client side ─────────────────────────────────────────────────────────────

    @Override
    public VRpcResponse call(String targetNodeId, String method, byte[] payload, Duration timeout) throws VRpcException {
        try {
            return callAsync(targetNodeId, method, payload, timeout).get(timeout.toMillis() + 100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VRpcException("RPC interrupted: " + method, e);
        } catch (TimeoutException e) {
            throw new VRpcException("RPC timed out after " + timeout + ": " + method, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof VRpcException ve) throw ve;
            throw new VRpcException("RPC failed: " + method, cause);
        }
    }

    @Override
    public CompletableFuture<VRpcResponse> callAsync(String targetNodeId, String method, byte[] payload, Duration timeout) {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<VRpcResponse> future = new CompletableFuture<>();
        pending.put(correlationId, future);

        // Timeout watchdog on a virtual thread.
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(timeout.toMillis());
                CompletableFuture<VRpcResponse> f = pending.remove(correlationId);
                if (f != null && !f.isDone()) {
                    f.completeExceptionally(new VRpcException("RPC timed out after " + timeout + ": " + method));
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            Envelope req = Envelope.request(correlationId, method, localNodeId,
                    payload != null ? payload : new byte[0]);
            messaging.publish(reqChannel(targetNodeId), encode(req));
        } catch (IOException e) {
            pending.remove(correlationId);
            future.completeExceptionally(new VRpcException("Failed to send RPC: " + method, e));
        }
        return future;
    }

    private void onResponse(byte[] frame) {
        try {
            Envelope env = decode(frame);
            if (env.type == TYPE_REQUEST) return;
            CompletableFuture<VRpcResponse> future = pending.remove(env.correlationId);
            if (future == null) return; // already timed out / not ours
            if (env.type == TYPE_RESPONSE_OK) {
                future.complete(VRpcResponse.ok(env.payload));
            } else {
                future.complete(VRpcResponse.error(env.errorOrMethod));
            }
        } catch (IOException e) {
            log.warn("[RPC] Dropping malformed response frame", e);
        }
    }

    /** Unsubscribes the messaging listeners. */
    public void shutdown() {
        messaging.unsubscribe(reqChannel(localNodeId), requestListener);
        messaging.unsubscribe(respChannel(localNodeId), responseListener);
        pending.values().forEach(f -> f.completeExceptionally(new VRpcException("RPC service shut down")));
        pending.clear();
    }

    // ── channels ────────────────────────────────────────────────────────────────

    private static String reqChannel(String nodeId)  { return "vatn.rpc.req." + nodeId; }
    private static String respChannel(String nodeId) { return "vatn.rpc.resp." + nodeId; }

    // ── framing ─────────────────────────────────────────────────────────────────

    private static byte[] encode(Envelope e) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(e.type);
            out.writeUTF(e.correlationId);
            out.writeUTF(e.errorOrMethod != null ? e.errorOrMethod : "");
            out.writeUTF(e.callerNodeId != null ? e.callerNodeId : "");
            byte[] p = e.payload != null ? e.payload : new byte[0];
            out.writeInt(p.length);
            out.write(p);
        }
        return bos.toByteArray();
    }

    private static Envelope decode(byte[] frame) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame))) {
            Envelope e = new Envelope();
            e.type = in.readByte();
            e.correlationId = in.readUTF();
            e.errorOrMethod = in.readUTF();
            e.callerNodeId = in.readUTF();
            int len = in.readInt();
            e.payload = new byte[Math.max(0, len)];
            in.readFully(e.payload);
            return e;
        }
    }

    /** {@code errorOrMethod} carries the method name on requests and the error message on errors. */
    private static final class Envelope {
        byte type;
        String correlationId;
        String errorOrMethod;
        String callerNodeId;
        byte[] payload;

        static Envelope request(String cid, String method, String caller, byte[] payload) {
            Envelope e = new Envelope();
            e.type = TYPE_REQUEST; e.correlationId = cid; e.errorOrMethod = method;
            e.callerNodeId = caller; e.payload = payload;
            return e;
        }
        static Envelope ok(String cid, String caller, byte[] payload) {
            Envelope e = new Envelope();
            e.type = TYPE_RESPONSE_OK; e.correlationId = cid; e.errorOrMethod = "";
            e.callerNodeId = caller; e.payload = payload;
            return e;
        }
        static Envelope error(String cid, String caller, String message) {
            Envelope e = new Envelope();
            e.type = TYPE_RESPONSE_ERR; e.correlationId = cid; e.errorOrMethod = message;
            e.callerNodeId = caller; e.payload = new byte[0];
            return e;
        }
    }
}
