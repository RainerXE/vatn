package dev.vatn.plugins.terminalphone;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

class AudioCapture implements AutoCloseable {

    static final AudioFormat FORMAT = new AudioFormat(16_000f, 16, 1, true, false);

    private final TargetDataLine line;
    private final AtomicBoolean  recording = new AtomicBoolean(false);

    AudioCapture() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Microphone line not supported on this platform");
        }
        line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(FORMAT);
    }

    byte[] capture(int durationMs) throws IOException {
        if (!recording.compareAndSet(false, true)) {
            throw new IllegalStateException("Already recording");
        }
        try {
            line.start();
            int    bytesPerMs = (int) (FORMAT.getSampleRate() * FORMAT.getFrameSize() / 1000);
            int    totalBytes = durationMs * bytesPerMs;
            byte[] buffer     = new byte[totalBytes];
            int    read       = 0;
            while (read < totalBytes && recording.get()) {
                int n = line.read(buffer, read, Math.min(4096, totalBytes - read));
                if (n > 0) read += n;
            }
            return Arrays.copyOf(buffer, read);
        } finally {
            line.stop();
            line.flush();
            recording.set(false);
        }
    }

    void stopCapture() {
        recording.set(false);
    }

    @Override
    public void close() {
        line.close();
    }
}
