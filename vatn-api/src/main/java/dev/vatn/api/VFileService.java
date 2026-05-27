package dev.vatn.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Universal File System SPI.
 * Decouples agents/tools from the local host file system.
 * Allows execution in restricted sandboxes (WASM, Docker) or cloud buckets.
 */
@VatnApi(since = "1.0")
public interface VFileService extends VService {
    
    /**
     * Reads a file as a string.
     */
    String readString(Path path) throws IOException;

    /**
     * Writes a string to a file.
     */
    void writeString(Path path, String content) throws IOException;

    /**
     * Checks if a file exists.
     */
    boolean exists(Path path);

    /**
     * Lists files in a directory.
     */
    List<Path> list(Path path) throws IOException;

    /**
     * Opens an input stream for reading.
     */
    InputStream openInput(Path path) throws IOException;

    /**
     * Opens an output stream for writing.
     */
    OutputStream openOutput(Path path) throws IOException;
    
    /**
     * Deletes a file or directory.
     */
    void delete(Path path) throws IOException;
}
