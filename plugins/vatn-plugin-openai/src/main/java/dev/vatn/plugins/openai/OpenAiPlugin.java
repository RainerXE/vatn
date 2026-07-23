package dev.vatn.plugins.openai;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drop-in LLM client plugin for VATN. Registers an {@link LlmService} in the
 * node context that any downstream plugin or route handler can use.
 *
 * <pre>{@code
 * // OpenAI
 * VNodeRunner.create(8080)
 *     .addPlugin(new OpenAiPlugin(OpenAiConfig.openai("sk-…").withModel("gpt-4o")))
 *     .addPlugin(new MyAppPlugin())
 *     .start();
 *
 * // Anthropic / Claude
 * VNodeRunner.create(8080)
 *     .addPlugin(new OpenAiPlugin(OpenAiConfig.anthropic("sk-ant-…")))
 *     .addPlugin(new MyAppPlugin())
 *     .start();
 *
 * // Inside any plugin handler
 * LlmService llm = ctx.getService(LlmService.class).orElseThrow();
 * LlmResponse r  = llm.complete("Summarise: " + body);
 * }</pre>
 */
public class OpenAiPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(OpenAiPlugin.class);

    private final OpenAiConfig config;

    public OpenAiPlugin(OpenAiConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.openai"; }
    @Override public String getName()    { return "VATN OpenAI/Claude Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.15-preview"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        LlmService service = new OpenAiLlmService(config);
        ctx.registerService(LlmService.class, service);
        log.info("LlmService registered — provider={}, model={}", config.getProvider(), config.getModel());
    }

    @Override
    public void onShutdown() {}
}
