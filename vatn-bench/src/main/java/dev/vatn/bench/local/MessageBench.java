package dev.vatn.bench.local;

import dev.vatn.api.VMessaging;
import dev.vatn.bench.VNodeState;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VMessaging pub/sub throughput and latency.
 * Measures: fire-and-forget publish, round-trip latency, fan-out to N subscribers.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class MessageBench extends VNodeState {

    private VMessaging messaging;
    private byte[] payload;
    private final AtomicLong received = new AtomicLong();

    @Setup(Level.Trial)
    @Override
    public void startNode() throws Exception {
        super.startNode();
        messaging = context.getMessaging();
        payload = "{\"type\":\"bench\",\"value\":42}".getBytes(StandardCharsets.UTF_8);

        // Subscriber for round-trip test
        messaging.subscribe("bench.pong", msg -> received.incrementAndGet());

        // Fan-out subscriber setup (10 subscribers on same topic)
        for (int i = 0; i < 10; i++) {
            messaging.subscribe("bench.fanout", msg -> received.incrementAndGet());
        }
    }

    @Benchmark
    public void publish_fireAndForget() {
        messaging.publish("bench.fire", payload);
    }

    @Benchmark
    public long roundtrip_latency() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        long[] result = {0};
        long sent = System.nanoTime();
        messaging.subscribe("bench.rt.reply", msg -> {
            result[0] = System.nanoTime() - sent;
            latch.countDown();
        });
        messaging.publish("bench.rt.send", payload);
        messaging.subscribe("bench.rt.send", msg -> messaging.publish("bench.rt.reply", msg));
        latch.await(1, TimeUnit.SECONDS);
        return result[0];
    }

    @Benchmark
    public void publish_fanout_10() {
        messaging.publish("bench.fanout", payload);
    }

    @Benchmark
    public void publish_largePayload() {
        // 10KB payload — tests serialisation overhead
        byte[] large = new byte[10 * 1024];
        messaging.publish("bench.large", large);
    }
}
