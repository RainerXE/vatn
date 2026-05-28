package dev.vatn.api;

import dev.vatn.api.VatnApi;

/**
 * Entry point for VATN's durable, persistent pub/sub topics.
 *
 * <p>A {@link VTopic} stores every published event in SQLite and tracks per-consumer offsets,
 * so consumers can replay missed events after a restart and multiple independent consumers can
 * read the same topic at different positions — analogous to Kafka consumer groups, but backed
 * by the node's existing SQLite database with no separate broker.
 *
 * <p>Use {@link VMessaging} for in-process ephemeral pub/sub where durability is not needed.
 * Use {@code VTopicService} when events must survive node restarts or need to reach multiple
 * independent consumers at different paces.
 *
 * <pre>{@code
 * VTopicService topics = ctx.getService(VTopicService.class).orElseThrow();
 * VTopic events = topics.topic("user-events");
 *
 * // Publisher
 * events.publish("{\"userId\":42,\"action\":\"login\"}");
 *
 * // Consumer — replays from last saved offset, then stays live
 * events.subscribe("audit-log", event -> auditLog.record(event.payload()));
 * events.subscribe("analytics", event -> analytics.ingest(event.payload()));
 * }</pre>
 *
 * <p>Inspired by <a href="https://github.com/russellromney/honker">honker</a>'s durable stream
 * semantics: each named consumer owns a row in {@code vatn_topic_offsets} that advances as events
 * are processed. A crash re-delivers in-flight events from the last flushed offset.
 *
 * @see VTopic
 * @see VMessaging
 */
@VatnApi(since = "1.0-alpha.9")
public interface VTopicService extends VService {

    /**
     * Returns a topic handle. The topic is created lazily on first publish.
     * Multiple calls with the same name return handles backed by the same rows.
     */
    VTopic topic(String name);
}
