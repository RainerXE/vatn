package dev.vatn.examples.guard;

import dev.vatn.api.VGuardService;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the custom ContentFilterGuard, replacing the default vatn-core
 * passthrough guard for the lifetime of this node.
 *
 * <p>The last plugin to call {@code context.registerService(VGuardService.class, ...)}
 * wins, so a guard registered here supersedes any earlier registration.
 */
public class GuardDemoPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(GuardDemoPlugin.class);

    @Override public String getId()      { return "dev.vatn.examples.custom-guard"; }
    @Override public String getName()    { return "Custom Guard Demo"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        ctx.registerService(VGuardService.class, new ContentFilterGuard());
        log.info("ContentFilterGuard registered — PII redaction + SSRF blocking active.");
    }
}
