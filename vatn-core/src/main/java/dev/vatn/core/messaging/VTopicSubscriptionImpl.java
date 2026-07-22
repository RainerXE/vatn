package dev.vatn.core.messaging;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.VTopic;
import dev.vatn.api.VTopicEvent;
import dev.vatn.api.VTopicSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

class VTopicSubscriptionImpl implements VTopicSubscription {
    private static final Logger log = LoggerFactory.getLogger(VTopicSubscriptionImpl.class);

    private static final int POLL_INTERVAL_MS = Integer.getInteger("vatn.topic.pollIntervalMs", 100);
    private static final int OFFSET_SAVE_EVENTS = 1_000;
    private static final long OFFSET_SAVE_INTERVAL_MS = 1_000;

    private final VTopicImpl topic;
    private final String consumerId;
    private final AtomicLong offset;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Thread thread;

    VTopicSubscriptionImpl(VTopicImpl topic, String consumerId, long initialOffset, VPersistenceService db) {
        this.topic = topic;
        this.consumerId = consumerId;
        this.offset = new AtomicLong(initialOffset);
    }

    void start(VTopic.EventHandler handler) {
        thread = Thread.ofVirtual().name("vatn-topic-" + topic.name() + "-" + consumerId)
            .start(() -> pollLoop(handler));
    }

    private void pollLoop(VTopic.EventHandler handler) {
        long eventsSinceLastSave = 0;
        long lastSaveTime = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (paused.get()) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }

                List<VTopicEvent> events = topic.readBatch(offset.get());

                if (events.isEmpty()) {
                    if (eventsSinceLastSave > 0) {
                        topic.saveOffset(consumerId, offset.get());
                        eventsSinceLastSave = 0;
                        lastSaveTime = System.currentTimeMillis();
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }

                for (VTopicEvent event : events) {
                    try {
                        handler.onEvent(event);
                    } catch (Exception e) {
                        log.warn("[VTopic-{}/{}] Handler error at event {}: {}",
                            topic.name(), consumerId, event.id(), e.getMessage());
                    }
                    offset.set(event.id());
                    eventsSinceLastSave++;
                }

                long now = System.currentTimeMillis();
                if (eventsSinceLastSave >= OFFSET_SAVE_EVENTS
                        || (now - lastSaveTime) >= OFFSET_SAVE_INTERVAL_MS) {
                    topic.saveOffset(consumerId, offset.get());
                    eventsSinceLastSave = 0;
                    lastSaveTime = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[VTopic-{}/{}] Poll loop error: {}", topic.name(), consumerId, e.getMessage());
                try { Thread.sleep(POLL_INTERVAL_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        topic.saveOffset(consumerId, offset.get());
    }

    @Override public String consumerId() { return consumerId; }
    @Override public String topic()      { return topic.name(); }
    @Override public long currentOffset() { return offset.get(); }

    @Override
    public void pause() { paused.set(true); }

    @Override
    public void resume() { paused.set(false); }

    @Override
    public void close() {
        if (thread != null) {
            thread.interrupt();
            try { thread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        topic.saveOffset(consumerId, offset.get());
    }
}
