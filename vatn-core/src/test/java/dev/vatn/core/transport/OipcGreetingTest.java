package dev.vatn.core.transport;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OipcGreetingTest {

    @Test
    void buildReturns64Bytes() {
        byte[] g = OipcGreeting.build("cli-1", new byte[24], OipcGreeting.TRANSPORT_TCP);
        assertEquals(64, g.length);
    }

    @Test
    void magicIsOIPC() {
        byte[] g = OipcGreeting.build("cli-1", new byte[24], OipcGreeting.TRANSPORT_TCP);
        assertArrayEquals("OIPC".getBytes(StandardCharsets.US_ASCII), new byte[] { g[0], g[1], g[2], g[3] });
    }

    @Test
    void versionBytes() {
        byte[] g = OipcGreeting.build("cli-1", new byte[24], OipcGreeting.TRANSPORT_TCP);
        assertEquals(2, g[4] & 0xFF);   // ver_major
        assertEquals(12, g[5] & 0xFF);  // ver_minor
    }

    @Test
    void transportByteAtOffset14() {
        byte[] g = OipcGreeting.build("cli-1", new byte[24], OipcGreeting.TRANSPORT_UDS);
        assertEquals(OipcGreeting.TRANSPORT_UDS, g[14] & 0xFF);
        byte[] t = OipcGreeting.build("cli-1", new byte[24], OipcGreeting.TRANSPORT_TCP);
        assertEquals(OipcGreeting.TRANSPORT_TCP, t[14] & 0xFF);
    }

    @Test
    void clientIdBytesPaddedOrTruncated() {
        byte[] g = OipcGreeting.build("cli-1", new byte[24], OipcGreeting.TRANSPORT_TCP);
        byte[] expected = new byte[16];
        byte[] src = "cli-1".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, expected, 0, src.length);
        assertArrayEquals(expected, slice(g, 19, 35));
    }

    @Test
    void clientIdTruncatedWhenLongerThan16() {
        String longId = "this-client-id-is-way-too-long";
        byte[] g = OipcGreeting.build(longId, new byte[24], OipcGreeting.TRANSPORT_TCP);
        byte[] src = longId.getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[16];
        System.arraycopy(src, 0, expected, 0, 16);
        assertArrayEquals(expected, slice(g, 19, 35));
    }

    @Test
    void authTokenBytesAt35to58() {
        byte[] token = new byte[24];
        for (int i = 0; i < token.length; i++) {
            token[i] = (byte) (i + 1);
        }
        byte[] g = OipcGreeting.build("cli-1", token, OipcGreeting.TRANSPORT_TCP);
        assertArrayEquals(token, slice(g, 35, 59));
    }

    @Test
    void tokenBytesEncodesTo24WithPadding() {
        byte[] t = OipcGreeting.tokenBytes("secret123");
        assertEquals(24, t.length);
        byte[] expected = new byte[24];
        byte[] src = "secret123".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, expected, 0, src.length);
        assertArrayEquals(expected, t);
    }

    @Test
    void flagsTunneledHttpBitSet() {
        byte[] g = OipcGreeting.build("cli-1", new byte[24], OipcGreeting.TRANSPORT_TCP, OipcGreeting.FLAG_TUNNELED_HTTP);
        int flags = (g[6] & 0xFF) | ((g[7] & 0xFF) << 8);
        assertEquals(OipcGreeting.FLAG_TUNNELED_HTTP, flags);
    }

    private static byte[] slice(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }
}
