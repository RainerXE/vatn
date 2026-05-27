package dev.vatn.api;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Universal Streaming service for high-volume binary data piping.
 * Allows plugins to process data (AI logs, video, logs) without loading it into the JVM heap.
 */
@VatnApi(since = "1.0")
public interface VStream extends VService {

    /**
     * Creates a named output stream with specific flow policies.
     * 
     * @param streamId The identifier for the stream.
     * @param policy The flow policy (Mediated vs Direct, Direction).
     * @return Output stream for the plugin to write to.
     */
    OutputStream createPolicyStream(String streamId, dev.vatn.api.security.VFlowPolicy policy);

    /**
     * Creates a named output stream that other plugins can consume.
     */
    OutputStream createOutput(String streamId);

    /**
     * Opens an input stream provided by another plugin or the host.
     */
    InputStream openInput(String streamId);

    /**
     * Efficiently pipes data from an input to an output stream using virtual threads.
     */
    void pipe(InputStream in, OutputStream out);

    /**
     * Creates an output stream that pipes data directly to a remote VATN node URL.
     */
    OutputStream createRemoteOutput(String targetUrl);
}
