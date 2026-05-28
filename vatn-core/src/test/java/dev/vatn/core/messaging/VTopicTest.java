package dev.vatn.core.messaging;

import dev.vatn.api.VTopic;
import dev.vatn.api.VTopicEvent;
import dev.vatn.api.VTopicService;
import dev.vatn.api.VTopicSubscription;
import dev.vatn.core.memory.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VTopicTest {

    @TempDir Path tempDir;
    private DatabaseManager db;
    private VTopicService topicService;

    @BeforeEach
    void setUp() {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath();
        db = new DatabaseManager(jdbcUrl);
        db.registerSchemaContributor(new VatnMessagingSchemaContributor());
        topicService = new VTopicServiceImpl(db);
    }

    @Test
    void publishAndSubscribeDelivers() throws Exception {
        VTopic topic = topicService.topic("events.v1");
        CountDownLatch latch = new CountDownLatch(3);
        List<VTopicEvent> received = new CopyOnWriteArrayList<>();

        VTopicSubscription sub = topic.subscribe("c1", event -> {
            received.add(event);
            latch.countDown();
        });

        topic.publish("msg-1");
        topic.publish("msg-2");
        topic.publish("msg-3");

        assertTrue(latch.await(3, TimeUnit.SECONDS), "All 3 events should arrive within 3 seconds");
        assertEquals(3, received.size());

        sub.close();
    }

    @Test
    void replayFromSavedOffset() throws Exception {
        VTopic topic = topicService.topic("events.replay");

        for (int i = 1; i <= 5; i++) {
            topic.publish("msg-" + i);
        }

        topic.seek("c2", 2);

        CountDownLatch latch = new CountDownLatch(3);
        List<Long> receivedIds = new CopyOnWriteArrayList<>();

        VTopicSubscription sub = topic.subscribe("c2", event -> {
            receivedIds.add(event.id());
            latch.countDown();
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS), "Events 3-5 should arrive");
        assertEquals(3, receivedIds.size());
        assertTrue(receivedIds.stream().allMatch(id -> id >= 3),
            "Only events after offset 2 should be replayed");

        sub.close();
    }

    @Test
    void multipleIndependentConsumers() throws Exception {
        VTopic topic = topicService.topic("events.fanout");
        CountDownLatch auditLatch = new CountDownLatch(3);
        CountDownLatch analyticsLatch = new CountDownLatch(3);
        List<VTopicEvent> auditEvents = new CopyOnWriteArrayList<>();
        List<VTopicEvent> analyticsEvents = new CopyOnWriteArrayList<>();

        VTopicSubscription auditSub = topic.subscribe("audit", event -> {
            auditEvents.add(event);
            auditLatch.countDown();
        });
        VTopicSubscription analyticsSub = topic.subscribe("analytics", event -> {
            analyticsEvents.add(event);
            analyticsLatch.countDown();
        });

        topic.publish("event-1");
        topic.publish("event-2");
        topic.publish("event-3");

        assertTrue(auditLatch.await(3, TimeUnit.SECONDS), "audit consumer should get all 3 events");
        assertTrue(analyticsLatch.await(3, TimeUnit.SECONDS), "analytics consumer should get all 3 events");
        assertEquals(3, auditEvents.size());
        assertEquals(3, analyticsEvents.size());

        auditSub.close();
        analyticsSub.close();
    }

    @Test
    void seekZeroReplaysAll() throws Exception {
        VTopic topic = topicService.topic("events.seekzero");

        topic.publish("e1");
        topic.publish("e2");
        topic.publish("e3");

        CountDownLatch firstLatch = new CountDownLatch(3);
        VTopicSubscription firstSub = topic.subscribe("c3", event -> firstLatch.countDown());
        assertTrue(firstLatch.await(3, TimeUnit.SECONDS), "First subscription should consume 3 events");
        firstSub.close();

        Thread.sleep(200);

        topic.seek("c3", 0);

        CountDownLatch replayLatch = new CountDownLatch(3);
        List<String> replayed = new CopyOnWriteArrayList<>();
        VTopicSubscription replaySub = topic.subscribe("c3", event -> {
            replayed.add(event.payload());
            replayLatch.countDown();
        });

        assertTrue(replayLatch.await(3, TimeUnit.SECONDS), "All events should replay from beginning");
        assertEquals(3, replayed.size());

        replaySub.close();
    }

    @Test
    void pauseResumeHoldsDelivery() throws Exception {
        VTopic topic = topicService.topic("events.pause");
        List<VTopicEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch afterResume = new CountDownLatch(3);

        VTopicSubscription sub = topic.subscribe("c4", event -> {
            received.add(event);
            afterResume.countDown();
        });

        sub.pause();

        topic.publish("while-paused-1");
        topic.publish("while-paused-2");
        topic.publish("while-paused-3");

        Thread.sleep(300);
        assertTrue(received.isEmpty(), "No events should be delivered while paused");

        sub.resume();

        assertTrue(afterResume.await(3, TimeUnit.SECONDS), "All events should arrive after resume");
        assertEquals(3, received.size());

        sub.close();
    }

    @Test
    void closeFlushesOffset() throws Exception {
        VTopic topic = topicService.topic("events.offset-flush");

        topic.publish("a");
        topic.publish("b");
        topic.publish("c");

        CountDownLatch latch = new CountDownLatch(3);
        VTopicSubscription sub = topic.subscribe("c5", event -> latch.countDown());

        assertTrue(latch.await(3, TimeUnit.SECONDS), "All 3 events should arrive");
        Thread.sleep(200);
        sub.close();
        Thread.sleep(100);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT offset FROM vatn_topic_offsets WHERE topic=? AND consumer_id=?")) {
            ps.setString(1, "events.offset-flush");
            ps.setString(2, "c5");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Offset record should exist after close");
                assertEquals(3, rs.getLong("offset"),
                    "Offset should be 3 after consuming all 3 events");
            }
        }
    }

    @Test
    void latestOffset_returnsHighestId() throws Exception {
        VTopic topic = topicService.topic("events.latest");

        assertEquals(0L, topic.latestOffset(), "Empty topic should have latestOffset=0");

        for (int i = 0; i < 5; i++) {
            topic.publish("msg-" + i);
        }

        assertEquals(5L, topic.latestOffset());
    }

    @Test
    void readBatch_returnsEventsAfterOffset() throws Exception {
        VTopic topic = topicService.topic("events.read");

        for (int i = 0; i < 5; i++) {
            topic.publish("msg-" + i);
        }

        List<VTopicEvent> events = topic.read(2, 10);
        assertEquals(3, events.size());
        assertEquals(3, events.get(0).id());
        assertEquals(4, events.get(1).id());
        assertEquals(5, events.get(2).id());
    }

    @Test
    void prune_deletesOldEvents() throws Exception {
        VTopic topic = topicService.topic("events.prune");

        for (int i = 0; i < 5; i++) {
            topic.publish("msg-" + i);
        }

        int deleted = topic.prune(3);
        assertEquals(3, deleted);

        assertEquals(5L, topic.latestOffset(), "latestOffset should not change after prune");

        List<VTopicEvent> remaining = topic.read(0, 100);
        assertEquals(2, remaining.size());
        assertEquals(4, remaining.get(0).id());
        assertEquals(5, remaining.get(1).id());
    }
}
