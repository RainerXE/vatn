package dev.vatn.plugins.terminalphone;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anonymous, E2E-encrypted voice and text over Tor hidden services.
 *
 * <p>Replicates the TerminalPhone walkie-talkie model: record a complete voice message,
 * encrypt it with AES-256-CBC (or another configurable cipher), sign it with HMAC-SHA256,
 * and transport it through the Tor SOCKS5 proxy to a peer's .onion address. Text messaging
 * and a zero-knowledge relay mode for group calls are also supported.
 *
 * <pre>{@code
 * runner.addPlugin(new TerminalPhonePlugin(
 *     TerminalPhoneConfig.builder("my-out-of-band-shared-secret")
 *         .cipher("AES-256-CBC")
 *         .torHost("127.0.0.1")
 *         .torPort(9050)
 *         .listenPort(54321)
 *         .hmacSigning(true)
 *         .relayMode(false)
 *         .build()
 * ));
 * }</pre>
 */
public class TerminalPhonePlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(TerminalPhonePlugin.class);

    private final TerminalPhoneConfig    config;
    private SessionManager               sessionManager;
    private TerminalPhoneServiceImpl     service;
    private RelaySession                 relaySession;

    public TerminalPhonePlugin(TerminalPhoneConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.terminalphone"; }
    @Override public String getName()    { return "VATN TerminalPhone Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Initializing VATN TerminalPhone Plugin");
        try {
            CryptoEngine crypto    = new CryptoEngine(config.sharedSecret(), config.cipher());
            TorTransport transport = new TorTransport(config.torSocksHost(), config.torSocksPort());

            sessionManager = new SessionManager(config, transport, crypto);
            service        = new TerminalPhoneServiceImpl(sessionManager);

            String localAddress = ctx.getNodeId() + ":" + config.listenPort();
            service.setLocalAddress(localAddress);
            sessionManager.start(localAddress);

            ctx.registerService(TerminalPhoneService.class, service);
            ctx.register("/terminalphone", new TerminalPhoneHttpService(service));
            ctx.registerHealthCheck("terminalphone", () -> true);

            if (config.relayMode()) {
                relaySession = new RelaySession(config.listenPort() + 1);
                log.info("Relay mode active on port {}", config.listenPort() + 1);
            }

            log.info("TerminalPhone ready — address={}, cipher={}, hmac={}, relay={}",
                localAddress, config.cipher(), config.hmacSigning(), config.relayMode());

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize TerminalPhone plugin", e);
        }
    }

    @Override
    public void onShutdown() {
        log.info("TerminalPhone shutting down");
        try { if (sessionManager != null) sessionManager.close(); } catch (Exception ignored) {}
        try { if (relaySession   != null) relaySession.close();   } catch (Exception ignored) {}
    }
}
