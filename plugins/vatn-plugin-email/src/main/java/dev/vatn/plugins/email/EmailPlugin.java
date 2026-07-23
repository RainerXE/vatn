package dev.vatn.plugins.email;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SMTP email plugin for VATN. Registers an {@link EmailService} in the node context.
 *
 * <pre>{@code
 * EmailConfig config = EmailConfig
 *     .of("smtp.gmail.com", 587, "bot@example.com", "app-password", "bot@example.com")
 *     .withStartTls(true);
 *
 * VNodeRunner.create(8080)
 *     .addPlugin(new EmailPlugin(config))
 *     .addPlugin(new MyAppPlugin())
 *     .start();
 *
 * // Inside a DAG operator or route handler
 * EmailService email = ctx.getService(EmailService.class).orElseThrow();
 * email.send("alice@example.com", "Pipeline complete", "All tasks succeeded.");
 * }</pre>
 */
public class EmailPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(EmailPlugin.class);

    private final EmailConfig config;

    public EmailPlugin(EmailConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.email"; }
    @Override public String getName()    { return "VATN Email Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.15-preview"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        EmailService service = new SmtpEmailService(config);
        ctx.registerService(EmailService.class, service);
        log.info("EmailService registered — host={}:{}", config.getHost(), config.getPort());
    }

    @Override
    public void onShutdown() {}
}
