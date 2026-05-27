package dev.vatn.examples.chat;

import dev.vatn.api.VMessaging;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VWsListener;
import dev.vatn.api.VWsSession;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatPlugin implements VNodePlugin {

    private static final String TOPIC = "chat.room";

    private VMessaging messaging;
    private final ConcurrentHashMap<String, VWsSession> sessions = new ConcurrentHashMap<>();

    @Override public String getId()      { return "dev.vatn.examples.ws-chat"; }
    @Override public String getName()    { return "WebSocket Chat"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        this.messaging = ctx.getMessaging();

        // Bridge the topic to every connected WebSocket session
        messaging.subscribe(TOPIC, payload -> {
            String message = new String(payload, StandardCharsets.UTF_8);
            sessions.values().forEach(session -> {
                try { session.send(message); } catch (Exception ignored) {}
            });
        });

        // WebSocket endpoint registered in Main.java via VNodeRunner.registerWebSocket()
    }

    public VWsListener createListener() {
        return new VWsListener() {
            @Override
            public void onOpen(VWsSession session) {
                String id = UUID.randomUUID().toString().substring(0, 8);
                sessions.put(id, session);
                session.send(json("welcome", id, null));
                broadcast(json("joined", id, null));
                // store id in session for later via a wrapper — we embed it in a closure below
                // (see ChatSession inner class for production usage)
            }

            @Override
            public void onMessage(VWsSession session, String text, boolean last) {
                // Find sender id by session reference
                String from = sessions.entrySet().stream()
                        .filter(e -> e.getValue() == session)
                        .map(java.util.Map.Entry::getKey)
                        .findFirst().orElse("unknown");
                broadcast(json("message", from, text));
            }

            @Override
            public void onClose(VWsSession session, int statusCode, String reason) {
                sessions.entrySet().removeIf(e -> {
                    if (e.getValue() == session) {
                        broadcast(json("left", e.getKey(), null));
                        return true;
                    }
                    return false;
                });
            }
        };
    }

    private void broadcast(String message) {
        messaging.publish(TOPIC, message.getBytes(StandardCharsets.UTF_8));
    }

    private static String json(String type, String sessionId, String text) {
        if (text != null) {
            return "{\"type\":\"" + type + "\",\"from\":\"" + sessionId
                    + "\",\"text\":\"" + escape(text) + "\"}";
        }
        return "{\"type\":\"" + type + "\",\"sessionId\":\"" + sessionId + "\"}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
