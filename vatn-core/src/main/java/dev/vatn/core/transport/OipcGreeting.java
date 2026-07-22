package dev.vatn.core.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Client-side builder for the OIPC v2.13 64-byte Greeting bootstrap.
 *
 * <p>Produces the byte array a Java binary client (or future polyglot bridge) must
 * emit before the V3 frame stream so the server can perform v2.13 identity /
 * auth handshake. The layout is little-endian for all multi-byte fields and is
 * byte-for-byte compatible with the server's accepted Greeting (see
 * {@code OipcMessagingTransport}).</p>
 *
 * <p>Dependency-free: only {@code java.nio} / {@code java.nio.charset}.</p>
 */
public final class OipcGreeting {

    public static final int SIZE = 64;
    public static final int AUTH_TOKEN_SIZE = 24;
    public static final int CLIENT_ID_SIZE = 16;

    public static final int TRANSPORT_UDS = 0x01;
    public static final int TRANSPORT_TCP = 0x02;

    /** Greeting_Flags bit: Tunneled_HTTP (v2.13). */
    public static final int FLAG_TUNNELED_HTTP = 1 << 3;

    private OipcGreeting() {
    }

    /**
     * Build a 64-byte Greeting with zero flags.
     */
    public static byte[] build(String clientId, byte[] authToken, int transport) {
        return build(clientId, authToken, transport, 0);
    }

    /**
     * Build a 64-byte Greeting with the supplied flags (u16 LE).
     */
    public static byte[] build(String clientId, byte[] authToken, int transport, int flags) {
        ByteBuffer bb = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("OIPC".getBytes(StandardCharsets.US_ASCII)); // 0..3 magic
        bb.put((byte) 2);                                   // 4 ver_major
        bb.put((byte) 12);                                  // 5 ver_minor (unchanged on wire)
        bb.putShort((short) (flags & 0xFFFF));              // 6..7 flags u16 LE
        bb.putInt(0);                                       // 8..11 codec_pref u32 LE
        bb.put((byte) 0);                                   // 12 mode_flags
        bb.put((byte) 0);                                   // 13 channel_mode
        bb.put((byte) transport);                           // 14 transport
        bb.putInt(0);                                       // 15..18 session_hint u32 LE

        byte[] cid = new byte[CLIENT_ID_SIZE];
        byte[] cidSrc = clientId.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(cidSrc, 0, cid, 0, Math.min(cidSrc.length, CLIENT_ID_SIZE));
        bb.put(cid);                                        // 19..34 client_id

        byte[] tok = new byte[AUTH_TOKEN_SIZE];
        if (authToken != null) {
            System.arraycopy(authToken, 0, tok, 0, Math.min(authToken.length, AUTH_TOKEN_SIZE));
        }
        bb.put(tok);                                        // 35..58 auth_token

        bb.put(new byte[5]);                                // 59..63 reserved
        return bb.array();
    }

    /**
     * Encode a String token to a fixed 24-byte array (UTF-8, zero-padded /
     * truncated), mirroring the server-side {@code expectedAuthTokenBytes}.
     */
    public static byte[] tokenBytes(String token) {
        byte[] out = new byte[AUTH_TOKEN_SIZE];
        if (token != null) {
            byte[] src = token.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(src, 0, out, 0, Math.min(src.length, AUTH_TOKEN_SIZE));
        }
        return out;
    }
}
