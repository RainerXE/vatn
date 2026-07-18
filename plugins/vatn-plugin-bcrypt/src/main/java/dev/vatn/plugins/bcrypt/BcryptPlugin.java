package dev.vatn.plugins.bcrypt;

import at.favre.lib.crypto.bcrypt.BCrypt;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BCrypt password hashing plugin for VATN.
 *
 * <pre>{@code
 * VNodeRunner.create(8080)
 *     .addPlugin(new BcryptPlugin())           // default cost factor 12
 *     .addPlugin(new BcryptPlugin(10))         // faster for tests / CI
 *     .addPlugin(new AuthPlugin(authConfig))   // pairs directly with auth
 *     .start();
 * }</pre>
 */
public class BcryptPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(BcryptPlugin.class);

    private final int costFactor;

    /** Default cost factor of 12 — ~250 ms on modern hardware, safe for production. */
    public BcryptPlugin() {
        this(12);
    }

    /** @param costFactor BCrypt work factor (4–31). Higher = slower = more secure. */
    public BcryptPlugin(int costFactor) {
        if (costFactor < 4 || costFactor > 31) throw new IllegalArgumentException("BCrypt cost factor must be 4–31");
        this.costFactor = costFactor;
    }

    @Override public String getId()      { return "dev.vatn.plugins.bcrypt"; }
    @Override public String getName()    { return "VATN BCrypt Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        BcryptService service = new BcryptService() {
            @Override
            public String hash(String plaintext) {
                return BCrypt.withDefaults().hashToString(costFactor, plaintext.toCharArray());
            }

            @Override
            public boolean verify(String plaintext, String hash) {
                return BCrypt.verifyer().verify(plaintext.toCharArray(), hash).verified;
            }
        };

        ctx.registerService(BcryptService.class, service);
        log.info("BcryptService registered — cost factor: {}", costFactor);
    }

    @Override
    public void onShutdown() {}
}
