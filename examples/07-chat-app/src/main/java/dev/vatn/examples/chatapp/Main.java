package dev.vatn.examples.chatapp;

import dev.vatn.core.VNodeRunner;

public class Main {
    public static void main(String[] args) {
        ChatAppPlugin plugin = new ChatAppPlugin();
        VNodeRunner runner = VNodeRunner.create(8080);
        runner.addPlugin(plugin);
        runner.registerWebSocket("/ws/chat", plugin.wsListener());
        runner.start();
    }
}
