package dev.vatn.api;

/**
 * Message types for the OIPC (Open Inter-Process Communication) protocol.
 * Aligned with OIPC v2.12 specification.
 */
@VatnApi(since = "1.0")
public enum VOipcMessageType {
    /** Fire-and-forget push message. */
    PUSH(0x01),
    
    /** Synchronous or asynchronous request. */
    REQUEST(0x02),
    
    /** Response to a previous request. */
    RESPONSE(0x03),
    
    /** A chunk of binary stream data. */
    STREAM_DATA(0x04),
    
    /** Protocol-level acknowledgment. */
    ACK(0x05),
    
    /** Protocol-level error signal. */
    ERROR(0x06),
    
    /** Subscribe to a channel/topic. */
    SUBSCRIBE(0x07),
    
    /** Unsubscribe from a channel/topic. */
    UNSUBSCRIBE(0x08),
    
    /** Connection keep-alive ping. */
    PING(0x09),
    
    /** Connection keep-alive pong. */
    PONG(0x0A),
    
    /** Graceful connection shutdown. */
    SHUTDOWN(0x0B);

    private final int opcode;

    VOipcMessageType(int opcode) {
        this.opcode = opcode;
    }

    public int getOpcode() {
        return opcode;
    }

    public static VOipcMessageType fromOpcode(int opcode) {
        for (VOipcMessageType type : values()) {
            if (type.opcode == opcode) {
                return type;
            }
        }
        return null;
    }
}
