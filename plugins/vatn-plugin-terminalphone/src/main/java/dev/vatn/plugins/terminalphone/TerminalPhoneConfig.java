package dev.vatn.plugins.terminalphone;

public record TerminalPhoneConfig(
    String  sharedSecret,
    String  cipher,
    String  torSocksHost,
    int     torSocksPort,
    int     listenPort,
    boolean hmacSigning,
    boolean relayMode
) {
    public static Builder builder(String sharedSecret) {
        return new Builder(sharedSecret);
    }

    public static final class Builder {
        private final String sharedSecret;
        private String  cipher       = "AES-256-CBC";
        private String  torSocksHost = "127.0.0.1";
        private int     torSocksPort = 9050;
        private int     listenPort   = 54321;
        private boolean hmacSigning  = true;
        private boolean relayMode    = false;

        Builder(String sharedSecret) {
            this.sharedSecret = sharedSecret;
        }

        public Builder cipher(String c)       { cipher       = c; return this; }
        public Builder torHost(String h)      { torSocksHost = h; return this; }
        public Builder torPort(int p)         { torSocksPort = p; return this; }
        public Builder listenPort(int p)      { listenPort   = p; return this; }
        public Builder hmacSigning(boolean b) { hmacSigning  = b; return this; }
        public Builder relayMode(boolean b)   { relayMode    = b; return this; }

        public TerminalPhoneConfig build() {
            if (sharedSecret == null || sharedSecret.isBlank()) {
                throw new IllegalStateException("sharedSecret must not be blank");
            }
            return new TerminalPhoneConfig(
                sharedSecret, cipher, torSocksHost, torSocksPort, listenPort, hmacSigning, relayMode);
        }
    }
}
