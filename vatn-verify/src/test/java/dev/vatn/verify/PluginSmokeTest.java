package dev.vatn.verify;

import dev.vatn.plugins.admin.AdminPlugin;
import dev.vatn.plugins.bcrypt.BcryptPlugin;
import dev.vatn.plugins.containers.ContainersPlugin;
import dev.vatn.plugins.cors.CorsPlugin;
import dev.vatn.plugins.devenv.DevEnvPlugin;
import dev.vatn.plugins.fts.FtsPlugin;
import dev.vatn.plugins.indexer.IndexerPlugin;
import dev.vatn.plugins.metrics.MetricsPlugin;
import dev.vatn.plugins.node.NodePlugin;
import dev.vatn.plugins.python.PythonPlugin;
import dev.vatn.plugins.scraper.ScraperPlugin;
import dev.vatn.plugins.security.SecurityPlugin;
import dev.vatn.plugins.wasm.WasmPlugin;
import dev.vatn.test.VPluginSmokeExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("smoke")
class PluginSmokeTest {

    @Test void adminPlugin()      { VPluginSmokeExtension.assertPluginBoots(new AdminPlugin()); }
    @Test void bcryptPlugin()     { VPluginSmokeExtension.assertPluginBoots(new BcryptPlugin()); }
    @Test void containersPlugin() { VPluginSmokeExtension.assertPluginBoots(new ContainersPlugin()); }
    @Test void corsPlugin()       { VPluginSmokeExtension.assertPluginBoots(new CorsPlugin()); }
    @Test void devenvPlugin()     { VPluginSmokeExtension.assertPluginBoots(new DevEnvPlugin()); }
    @Test void ftsPlugin()       { VPluginSmokeExtension.assertPluginBoots(new FtsPlugin()); }
    @Test void indexerPlugin()   { VPluginSmokeExtension.assertPluginBoots(new IndexerPlugin()); }
    @Test void metricsPlugin()    { VPluginSmokeExtension.assertPluginBoots(new MetricsPlugin()); }
    @Test void nodePlugin()       { VPluginSmokeExtension.assertPluginBoots(new NodePlugin()); }
    @Test void pythonPlugin()     { VPluginSmokeExtension.assertPluginBoots(new PythonPlugin()); }
    @Test void scraperPlugin()   { VPluginSmokeExtension.assertPluginBoots(new ScraperPlugin()); }
    @Test void securityPlugin()   { VPluginSmokeExtension.assertPluginBoots(new SecurityPlugin()); }
    @Test void wasmPlugin()      { VPluginSmokeExtension.assertPluginBoots(new WasmPlugin()); }
}
