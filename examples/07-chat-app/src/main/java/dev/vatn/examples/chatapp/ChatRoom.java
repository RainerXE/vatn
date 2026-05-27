package dev.vatn.examples.chatapp;

import dev.vatn.api.VMessaging;
import dev.vatn.api.VWsSession;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages connected users, history, and pub/sub fan-out for a single chat room.
 */
class ChatRoom {

    private static final int HISTORY_LIMIT = 50;
    private static final String TOPIC = "chat.messages";

    private final VMessaging messaging;

    // session → username (only after JOIN)
    private final ConcurrentHashMap<VWsSession, String> sessionUsers = new ConcurrentHashMap<>();
    // session → its messaging callback (so we can unsubscribe on close)
    private final ConcurrentHashMap<VWsSession, Consumer<byte[]>> sessionCallbacks = new ConcurrentHashMap<>();
    // ring buffer of recent messages
    private final CopyOnWriteArrayList<ChatMessage> history = new CopyOnWriteArrayList<>();

    ChatRoom(VMessaging messaging) {
        this.messaging = messaging;
    }

    /** Called when a WebSocket connection is opened (before username is known). */
    void onConnect(VWsSession session) {
        Consumer<byte[]> callback = payload -> {
            try {
                session.send(new String(payload, StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        };
        sessionCallbacks.put(session, callback);
        messaging.subscribe(TOPIC, callback);
    }

    /**
     * Called for every incoming text frame.
     * Returns false if the message should be ignored (e.g. protocol error).
     */
    boolean onMessage(VWsSession session, String text) {
        String type = extractField(text, "type");
        if (type == null) return false;

        return switch (type) {
            case "join"    -> handleJoin(session, text);
            case "message" -> handleMessage(session, text);
            case "typing"  -> handleTyping(session, text);
            default        -> false;
        };
    }

    /** Called when the WebSocket connection closes. */
    void onClose(VWsSession session) {
        // Unsubscribe this session from the broadcast topic
        Consumer<byte[]> callback = sessionCallbacks.remove(session);
        if (callback != null) messaging.unsubscribe(TOPIC, callback);

        String username = sessionUsers.remove(session);
        if (username != null) {
            broadcast(systemEvent("user_left", username));
        }
    }

    // ── event handlers ────────────────────────────────────────────────────────

    private boolean handleJoin(VWsSession session, String json) {
        String username = extractField(json, "username");
        if (username == null || username.isBlank()) return false;
        username = username.trim();
        if (username.length() > 32) username = username.substring(0, 32);

        // Reject duplicate names
        if (sessionUsers.containsValue(username)) {
            session.send("{\"type\":\"error\",\"message\":\"Username already taken\"}");
            return false;
        }

        sessionUsers.put(session, username);

        // Send history + current user list directly to the new joiner
        List<String> historyJson = new ArrayList<>();
        for (ChatMessage m : history) historyJson.add(m.toJson());

        session.send("{\"type\":\"welcome\",\"username\":\"" + escape(username) + "\","
                + "\"history\":[" + String.join(",", historyJson) + "],"
                + "\"users\":" + usersJson() + "}");

        // Broadcast join notification to everyone (including the new user)
        broadcast(systemEvent("user_joined", username));
        return true;
    }

    private boolean handleMessage(VWsSession session, String json) {
        String username = sessionUsers.get(session);
        if (username == null) return false; // not joined yet

        String text = extractField(json, "text");
        if (text == null || text.isBlank()) return false;
        text = text.trim();
        if (text.length() > 2000) text = text.substring(0, 2000);

        ChatMessage msg = ChatMessage.of(username, text);
        addToHistory(msg);
        broadcast(msg.toJson());
        return true;
    }

    private boolean handleTyping(VWsSession session, String json) {
        String username = sessionUsers.get(session);
        if (username == null) return false;

        String isTypingStr = extractField(json, "isTyping");
        boolean isTyping = "true".equalsIgnoreCase(isTypingStr);

        // Broadcast only to OTHER users (skip sender)
        String event = "{\"type\":\"typing\",\"username\":\"" + escape(username)
                + "\",\"isTyping\":" + isTyping + "}";
        broadcastExcept(session, event);
        return true;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void broadcast(String json) {
        messaging.publish(TOPIC, json.getBytes(StandardCharsets.UTF_8));
    }

    private void broadcastExcept(VWsSession exclude, String json) {
        sessionUsers.forEach((s, u) -> {
            if (s != exclude) {
                try { s.send(json); } catch (Exception ignored) {}
            }
        });
    }

    private String systemEvent(String type, String username) {
        return "{\"type\":\"" + type + "\",\"username\":\"" + escape(username)
                + "\",\"users\":" + usersJson() + "}";
    }

    private String usersJson() {
        List<String> names = new ArrayList<>(sessionUsers.values());
        Collections.sort(names);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(names.get(i))).append("\"");
        }
        return sb.append("]").toString();
    }

    private void addToHistory(ChatMessage msg) {
        history.add(msg);
        while (history.size() > HISTORY_LIMIT) history.remove(0);
    }

    /** Minimal JSON string field extractor — avoids a JSON library dependency. */
    static String extractField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        // Check for boolean/number value (no quotes)
        int valueStart = colon + 1;
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;
        if (valueStart >= json.length()) return null;
        char first = json.charAt(valueStart);
        if (first != '"') {
            // boolean or number — read until comma or }
            int end = valueStart;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(valueStart, end).trim();
        }
        int start = valueStart;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
