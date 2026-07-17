package dev.vatn.plugins.terminalphone;

public record PhoneMessage(Type type, byte[] payload) {

    public enum Type {
        HANDSHAKE(0x01),
        VOICE    (0x02),
        TEXT     (0x03),
        HANGUP   (0x04),
        RELAY    (0x05);

        final byte code;

        Type(int code) {
            this.code = (byte) code;
        }

        static Type fromCode(byte code) {
            for (Type t : values()) {
                if (t.code == code) return t;
            }
            throw new IllegalArgumentException("Unknown message type: 0x" + Integer.toHexString(code & 0xFF));
        }
    }
}
