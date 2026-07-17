package dev.vatn.plugins.comm;

/** Handler called for each normalized inbound message. */
@FunctionalInterface
public interface MessageHandler {
    void handle(InboundMessage message);
}
