package dev.vatn.plugins.terminalphone;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

class AudioPlayer {

    void play(byte[] pcmData) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, AudioCapture.FORMAT);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
            line.open(AudioCapture.FORMAT);
            line.start();
            int offset = 0;
            while (offset < pcmData.length) {
                int len = Math.min(4096, pcmData.length - offset);
                line.write(pcmData, offset, len);
                offset += len;
            }
            line.drain();
        }
    }
}
