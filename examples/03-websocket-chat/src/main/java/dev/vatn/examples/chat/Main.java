package dev.vatn.examples.chat;

import dev.vatn.core.VNodeRunner;

public class Main {
    public static void main(String[] args) {
        ChatPlugin plugin = new ChatPlugin();
        VNodeRunner runner = VNodeRunner.create(8080);
        runner.addPlugin(plugin);
        runner.registerWebSocket("/ws/chat", plugin.createListener());
        runner.start();
    }
}
