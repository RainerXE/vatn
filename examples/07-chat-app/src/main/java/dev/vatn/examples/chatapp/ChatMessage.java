package dev.vatn.examples.chatapp;

import java.time.Instant;

/**
 * Immutable chat message stored in the history ring buffer.
 */
record ChatMessage(String username, String text, long ts) {

    static ChatMessage of(String username, String text) {
        return new ChatMessage(username, text, Instant.now().toEpochMilli());
    }

    String toJson() {
        return "{\"type\":\"message\",\"username\":\"" + escape(username)
                + "\",\"text\":\"" + escape(text)
                + "\",\"ts\":" + ts + "}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
