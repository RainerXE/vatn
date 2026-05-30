package dev.vatn.core;

import dev.vatn.api.VRpcException;
import dev.vatn.api.VRpcResponse;
import dev.vatn.core.rpc.VRpcServiceImpl;
import dev.vatn.core.transport.InProcessMessaging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests VRpcServiceImpl using two service instances sharing one InProcessMessaging bus,
 * avoiding the need to spin up a full VNodeRunner.
 */
class VRpcServiceTest {

    private InProcessMessaging bus;
    private VRpcServiceImpl rpcA;
    private VRpcServiceImpl rpcB;

    @BeforeEach
    void setUp() {
        bus  = new InProcessMessaging();
        rpcA = new VRpcServiceImpl("node-a", bus);
        rpcB = new VRpcServiceImpl("node-b", bus);
    }

    @AfterEach
    void tearDown() {
        rpcA.shutdown();
        rpcB.shutdown();
    }

    @Test
    void loopbackCallEchoesPayload() throws Exception {
        rpcA.register("echo", req -> req.payload());

        VRpcResponse resp = rpcA.call("node-a", "echo",
                "hello".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));

        assertTrue(resp.ok());
        assertEquals("hello", new String(resp.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void crossNodeCallDelivered() throws Exception {
        rpcB.register("ping", req -> "pong".getBytes(StandardCharsets.UTF_8));

        VRpcResponse resp = rpcA.call("node-b", "ping", new byte[0], Duration.ofSeconds(2));

        assertTrue(resp.ok());
        assertEquals("pong", new String(resp.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void handlerExceptionBecomesErrorResponse() throws Exception {
        rpcA.register("boom", req -> { throw new RuntimeException("expected failure"); });

        VRpcResponse resp = rpcA.call("node-a", "boom", new byte[0], Duration.ofSeconds(2));

        assertFalse(resp.ok());
        assertTrue(resp.errorMessage().contains("expected failure"));
    }

    @Test
    void unknownMethodReturnsErrorResponse() throws Exception {
        VRpcResponse resp = rpcA.call("node-a", "no-such-method", new byte[0], Duration.ofSeconds(2));

        assertFalse(resp.ok());
        assertNotNull(resp.errorMessage());
    }

    @Test
    void timeoutThrowsVRpcException() {
        // "node-c" has no RPC service listening — request is published but no response ever arrives
        assertThrows(VRpcException.class, () ->
                rpcA.call("node-c", "anything", new byte[0], Duration.ofMillis(200)));
    }

    @Test
    void asyncCallCompletesNormally() throws Exception {
        rpcA.register("double", req -> {
            int n = Integer.parseInt(new String(req.payload(), StandardCharsets.UTF_8));
            return String.valueOf(n * 2).getBytes(StandardCharsets.UTF_8);
        });

        CompletableFuture<VRpcResponse> future = rpcA.callAsync(
                "node-a", "double", "21".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));

        VRpcResponse resp = future.get(3, TimeUnit.SECONDS);
        assertTrue(resp.ok());
        assertEquals("42", new String(resp.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void unregisterStopsHandling() throws Exception {
        rpcA.register("temp", req -> "ok".getBytes(StandardCharsets.UTF_8));
        rpcA.unregister("temp");

        VRpcResponse resp = rpcA.call("node-a", "temp", new byte[0], Duration.ofMillis(400));
        assertFalse(resp.ok(), "unregistered handler must return error");
    }
}
