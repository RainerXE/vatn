package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import dev.vatn.api.VStream;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 100-Node High-Concurrency Swarm Test.
 * Verifies that a single VATN node can handle 100 concurrent incoming streams 
 * from 100 different logical nodes, proving scalability for agentic swarms.
 */
public class VolumeStressTest {

    @Test
    public void test100NodeSwarmFanIn() throws Exception {
        int workerCount = 100;
        List<VNodeRunner> workers = new ArrayList<>();
        
        // 1. Start the Sink Node
        VNodeRunner sink = VNodeRunner.create(0);
        sink.start();
        int sinkPort = sink.getBoundPort();
        System.out.println("Sink node started on port: " + sinkPort);

        // 2. Start the Worker Swarm
        System.out.println("Starting " + workerCount + " worker nodes...");
        for (int i = 0; i < workerCount; i++) {
            VNodeRunner worker = VNodeRunner.create(0);
            worker.start();
            workers.add(worker);
        }

        try {
            System.out.println("Nodes are healthy. Initiating 100-node swarm transmission...");
            
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(workerCount);
            AtomicLong totalBytesReceived = new AtomicLong(0);

            // 3. Setup Consumer at Sink
            // In a real plugin, this would be a listener. Here we simulate 100 polls.
            for (int i = 0; i < workerCount; i++) {
                final String streamId = "swarm-stream-" + i;
                Thread.ofVirtual().start(() -> {
                    try {
                        VStream vStream = sink.getContext().getStream();
                        InputStream in = null;
                        for (int p = 0; p < 200; p++) {
                            try {
                                in = vStream.openInput(streamId);
                                break;
                            } catch (Exception e) {
                                java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
                            }
                        }
                        if (in != null) {
                            final InputStream finalIn = in;
                            try (finalIn) {
                                byte[] data = finalIn.readAllBytes();
                                totalBytesReceived.addAndGet(data.length);
                                latch.countDown();
                            }
                        }
                    } catch (RuntimeException | java.io.IOException e) {
                        System.err.println("Worker thread ingest error: " + e.getMessage());
                    }
                });
            }

            // 4. Workers drop data
            long startTime = System.currentTimeMillis();
            for (int i = 0; i < workerCount; i++) {
                final int idx = i;
                final String streamId = "swarm-stream-" + idx;
                final String targetUrl = "http://localhost:" + sinkPort + "/stream/" + streamId;
                
                Thread.ofVirtual().start(() -> {
                    try {
                        VStream vStream = workers.get(idx).getContext().getStream();
                        try (OutputStream out = vStream.createRemoteOutput(targetUrl)) {
                            out.write(("Data from worker " + idx).getBytes());
                        }
                    } catch (RuntimeException | java.io.IOException e) {
                        System.err.println("Worker stream emit error: " + e.getMessage());
                    }
                });
            }

            // 5. Verification
            boolean success = latch.await(30, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertTrue(success, "Not all workers delivered in time");
            long duration = System.currentTimeMillis() - startTime;
            
            System.out.println("Swarm test completed in " + duration + "ms");
            System.out.println("Total workers reporting: " + (workerCount - latch.getCount()));
            
            assertEquals(0, latch.getCount(), "Not all workers successfully delivered their streams");
            System.out.println("Architecture Scaling Verified: 100:1 Fan-in successful.");

        } finally {
            System.out.println("Stopping nodes...");
            workers.forEach(VNodeRunner::stop);
            sink.stop();
        }
    }
}
