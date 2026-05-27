package dev.vatn.core;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VUiService;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of VUiService that publishes events to the VATN messaging bus.
 */
public class VBusUiService implements VUiService {
    private static final Logger logger = LoggerFactory.getLogger(VBusUiService.class);
    private final VNodeContext context;
    private static final String TOPIC = "vatn.ui.updates";
    private static final int QUEUE_CAPACITY = 1000;

    // Each active SSE connection gets its own queue; events are fanned out to all of them.
    private final CopyOnWriteArrayList<LinkedBlockingQueue<String>> sseQueues = new CopyOnWriteArrayList<>();

    public VBusUiService(VNodeContext context) {
        this.context = context;
    }

    @Override
    public void onStream(String sessionId, String token) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("token", token != null ? token : "");
        publish(sessionId, "STREAM", data);
    }

    @Override
    public void onAction(String sessionId, String actionId, String status, Map<String, Object> metadata) {
        Map<String, Object> payload = new HashMap<>(metadata);
        payload.put("actionId", actionId);
        payload.put("status", status);
        publish(sessionId, "ACTION", payload);
    }

    @Override
    public void onMetrics(String sessionId, Map<String, Object> metrics) {
        publish(sessionId, "METRICS", metrics);
    }

    @Override
    public void onNotification(String sessionId, String type, String message) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", type != null ? type : "INFO");
        data.put("message", message != null ? message : "");
        publish(sessionId, "NOTIFICATION", data);
    }

    /**
     * Opens a new SSE event stream for a connecting client.
     * The caller must call {@link #closeEventStream} when the client disconnects.
     */
    public LinkedBlockingQueue<String> openEventStream() {
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        sseQueues.add(queue);
        return queue;
    }

    /** Removes the queue when the SSE client disconnects. */
    public void closeEventStream(LinkedBlockingQueue<String> queue) {
        sseQueues.remove(queue);
    }

    private void publish(String sessionId, String eventType, Map<String, Object> data) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("sessionId", sessionId);
            message.put("type", eventType);
            message.put("timestamp", System.currentTimeMillis());
            message.put("payload", data);

            String json = context.getJson().stringify(message);
            context.getMessaging().publish(TOPIC, json.getBytes(StandardCharsets.UTF_8));

            // Fan out to all active SSE connections (non-blocking: drops if queue is full)
            for (LinkedBlockingQueue<String> q : sseQueues) {
                if (!q.offer(json)) {
                    logger.debug("[VUI-BUS] SSE queue full — dropped event for slow consumer");
                }
            }
        } catch (Exception e) {
            logger.error("[VUI-BUS] Failed to publish UI update: {}", e.getMessage());
        }
    }
}
