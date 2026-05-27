package dev.vatn.core;

import dev.vatn.api.VFileService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Local implementation of VFileService using java.nio.file.
 * Simple passthrough to the host file system.
 */
public class LocalFileService implements VFileService {

    @Override
    public String readString(Path path) throws IOException {
        return Files.readString(path);
    }

    @Override
    public void writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public List<Path> list(Path path) throws IOException {
        try (Stream<Path> stream = Files.list(path)) {
            return stream.collect(Collectors.toList());
        }
    }

    @Override
    public InputStream openInput(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public OutputStream openOutput(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.newOutputStream(path);
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
