package dev.vatn.plugins.terminalphone;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

class MessageFramer {

    private static final int HMAC_LEN = 32;

    private final DataOutputStream out;
    private final DataInputStream  in;
    private final CryptoEngine     crypto;
    private final boolean          hmacEnabled;

    MessageFramer(OutputStream out, InputStream in, CryptoEngine crypto, boolean hmacEnabled) {
        this.out        = new DataOutputStream(out);
        this.in         = new DataInputStream(in);
        this.crypto     = crypto;
        this.hmacEnabled = hmacEnabled;
    }

    void send(PhoneMessage msg) throws Exception {
        byte[] encrypted = crypto.encrypt(msg.payload());
        byte[] typeAndPayload = prefixType(msg.type().code, encrypted);
        byte[] hmac = hmacEnabled ? crypto.sign(typeAndPayload) : new byte[0];

        int totalLen = 1 + (hmacEnabled ? HMAC_LEN : 0) + encrypted.length;
        out.writeInt(totalLen);
        out.writeByte(msg.type().code);
        if (hmacEnabled) out.write(hmac);
        out.write(encrypted);
        out.flush();
    }

    PhoneMessage receive() throws Exception {
        int totalLen = in.readInt();
        if (totalLen < 1) throw new IllegalStateException("Invalid frame length: " + totalLen);

        byte typeByte = in.readByte();
        PhoneMessage.Type type = PhoneMessage.Type.fromCode(typeByte);

        int encLen = totalLen - 1 - (hmacEnabled ? HMAC_LEN : 0);
        byte[] hmac      = hmacEnabled ? in.readNBytes(HMAC_LEN) : new byte[0];
        byte[] encrypted = in.readNBytes(encLen);

        if (hmacEnabled && !crypto.verify(prefixType(typeByte, encrypted), hmac)) {
            throw new SecurityException("HMAC verification failed — possible replay or tampering");
        }

        byte[] payload = crypto.decrypt(encrypted);
        return new PhoneMessage(type, payload);
    }

    private byte[] prefixType(byte type, byte[] data) {
        byte[] result = new byte[1 + data.length];
        result[0] = type;
        System.arraycopy(data, 0, result, 1, data.length);
        return result;
    }
}
