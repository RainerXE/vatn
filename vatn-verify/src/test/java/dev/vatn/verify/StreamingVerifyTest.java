package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import dev.vatn.api.VStream;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * High-integrity Federated Stress Test.
 * Verifies binary data piping across two logical nodes on different ports.
 */
public class StreamingVerifyTest {

    @Test
    public void testCrossNodeStreaming() throws Exception {
        VNodeRunner nodeEmitter = VNodeRunner.create(0);
        VNodeRunner nodeCollector = VNodeRunner.create(0);

        nodeEmitter.start();
        nodeCollector.start();

        try {
            VStream emitterStream = nodeEmitter.getContext().getStream();
            VStream collectorStream = nodeCollector.getContext().getStream();

            String streamId = "stress-test-" + System.currentTimeMillis();
            int mbOfData = 5; // 5MB stress test
            byte[] chunk = new byte[1024 * 1024]; // 1MB chunks
            AtomicLong totalReceived = new AtomicLong(0);
            CompletableFuture<Long> completionFuture = new CompletableFuture<>();

            // 1. Prepare Emitter (Node A)
            // It will pipe data to Node B's ingress URL
            String targetUrl = "http://localhost:" + nodeCollector.getBoundPort() + "/stream/" + streamId;
            OutputStream remoteOut = emitterStream.createRemoteOutput(targetUrl);
            
            // 2. Prepare Collector (Node B)
            // A virtual thread waits for the ingress stream to be registered
            Thread.ofVirtual().start(() -> {
                try {
                    // Poll for the stream to become available (it takes a moment to register via HTTP PUT)
                    InputStream in = null;
                    for (int i = 0; i < 50; i++) {
                        try {
                            in = collectorStream.openInput(streamId);
                            break;
                        } catch (Exception e) {
                            java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
                        }
                    }
                    
                    if (in != null) {
                        final InputStream finalIn = in;
                        try (finalIn) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                totalReceived.addAndGet(read);
                            }
                            completionFuture.complete(totalReceived.get());
                        }
                    } else {
                        completionFuture.completeExceptionally(new RuntimeException("Stream never appeared on Collector"));
                    }
                } catch (RuntimeException | java.io.IOException e) {
                    completionFuture.completeExceptionally(e);
                }
            });

            // 3. Emit Data
            Thread.ofVirtual().start(() -> {
                try (remoteOut) {
                    for (int i = 0; i < mbOfData; i++) {
                        remoteOut.write(chunk);
                        remoteOut.flush();
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

            // 4. Verify
            Long receivedBytes = completionFuture.get(10, TimeUnit.SECONDS);
            assertEquals((long) mbOfData * 1024 * 1024, receivedBytes, "Incomplete data transfer across nodes");
            
        } finally {
            nodeEmitter.stop();
            nodeCollector.stop();
        }
    }
}
