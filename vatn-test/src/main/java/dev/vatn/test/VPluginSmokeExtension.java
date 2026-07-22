package dev.vatn.test;

import dev.vatn.api.VNodePlugin;
import dev.vatn.core.VNodeRunner;

public class VPluginSmokeExtension {

    public static void assertPluginBoots(VNodePlugin plugin) {
        assertPluginBoots(plugin, 5000);
    }

    public static void assertPluginBoots(VNodePlugin plugin, long timeoutMs) {
        var runner = VNodeRunner.create(0);
        try {
            runner.addPlugin(plugin);
            runner.start();
        } finally {
            runner.stop();
        }
    }
}
