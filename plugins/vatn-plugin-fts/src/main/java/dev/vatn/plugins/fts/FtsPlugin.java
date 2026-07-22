package dev.vatn.plugins.fts;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full-text search plugin — registers an {@link FtsService} backed by SQLite FTS5 over the node's
 * existing database. No external search engine or extra infrastructure required.
 *
 * <pre>{@code
 * node.addPlugin(new FtsPlugin());
 * // …later, in another plugin:
 * FtsService fts = ctx.getService(FtsService.class).orElseThrow();
 * fts.index("books", id, title, blurb);
 * List<FtsResult> hits = fts.search("ursula le guin", 10);
 * }</pre>
 */
public class FtsPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(FtsPlugin.class);

    @Override public String getId()      { return "dev.vatn.plugins.fts"; }
    @Override public String getName()    { return "VATN Full-Text Search Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14-preview"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        VPersistenceService db = ctx.getService(VPersistenceService.class)
                .orElseThrow(() -> new IllegalStateException("FtsPlugin requires VPersistenceService"));
        ctx.registerService(FtsService.class, new FtsServiceImpl(db));
        log.info("Full-text search (SQLite FTS5) ready on node {}", ctx.getNodeId());
    }

    @Override
    public void onShutdown() {
        log.info("Full-text search plugin stopped.");
    }
}
