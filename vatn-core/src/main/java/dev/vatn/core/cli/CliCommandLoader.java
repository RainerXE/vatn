package dev.vatn.core.cli;

import dev.vatn.api.cli.VCliCommand;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Discovers {@link VCliCommand} contributions for a host CLI (vatn, frejay, …) via
 * {@link ServiceLoader}. This is the reusable, picocli-free half of the extensibility
 * mechanism (DCN-EXT-09); a host wraps the results in its own CLI library.
 */
public final class CliCommandLoader {

    private CliCommandLoader() {}

    /** Commands visible to the given classloader (services on the host classpath). */
    public static List<VCliCommand> discover(ClassLoader cl) {
        var out = new ArrayList<VCliCommand>();
        for (VCliCommand c : ServiceLoader.load(VCliCommand.class, cl)) {
            out.add(c);
        }
        return out;
    }

    /**
     * Commands contributed by plugin jars in {@code pluginsDir}, loaded over a child classloader
     * parented to {@code parent} (so {@code vatn-api}/core classes are shared). Missing or empty
     * directory → empty list.
     */
    public static List<VCliCommand> discoverFrom(Path pluginsDir, ClassLoader parent) {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) return List.of();
        URL[] jars;
        try (Stream<Path> s = Files.list(pluginsDir)) {
            jars = s.filter(p -> p.toString().endsWith(".jar"))
                    .map(CliCommandLoader::toUrl)
                    .filter(u -> u != null)
                    .toArray(URL[]::new);
        } catch (IOException e) {
            return List.of();
        }
        if (jars.length == 0) return List.of();
        URLClassLoader cl = new URLClassLoader(jars, parent);
        return discover(cl);
    }

    private static URL toUrl(Path p) {
        try { return p.toUri().toURL(); } catch (Exception e) { return null; }
    }
}
