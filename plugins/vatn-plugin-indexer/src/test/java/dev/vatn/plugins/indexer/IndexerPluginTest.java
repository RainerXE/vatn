package dev.vatn.plugins.indexer;

import dev.vatn.api.VJson;
import dev.vatn.api.VNodeContext;
import dev.vatn.core.VJsonImpl;
import dev.vatn.core.VNodeContextImpl;
import dev.vatn.core.security.VFirewallImpl;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexerPluginTest {

    // ---- InMemoryIndexService unit tests ----

    @Test
    void indexAndRetrieveDocument() {
        var service = new InMemoryIndexService();
        service.index("doc1", Map.of("title", "Hello", "content", "World"));

        var retrieved = service.get("doc1");
        assertNotNull(retrieved);
        assertEquals("Hello", retrieved.get("title"));
        assertEquals(1, service.size());
    }

    @Test
    void getReturnsNullForNonexistentId() {
        var service = new InMemoryIndexService();
        assertNull(service.get("nonexistent"));
    }

    @Test
    void searchMatchesTitle() {
        var service = new InMemoryIndexService();
        service.index("a", Map.of("title", "Hello World"));
        service.index("b", Map.of("title", "Goodbye World"));

        var results = service.search("Hello");
        assertEquals(1, results.size());
        assertEquals("Hello World", results.get(0).get("title"));
    }

    @Test
    void searchIsCaseInsensitive() {
        var service = new InMemoryIndexService();
        service.index("a", Map.of("title", "Hello World"));

        var results = service.search("hello");
        assertEquals(1, results.size());
    }

    @Test
    void clearEmptiesIndex() {
        var service = new InMemoryIndexService();
        service.index("a", Map.of("title", "Hello"));
        service.index("b", Map.of("title", "World"));
        assertEquals(2, service.size());

        service.clear();

        assertEquals(0, service.size());
        assertNull(service.get("a"));
        assertNull(service.get("b"));
    }

    @Test
    void indexBatchWithMultipleDocs() {
        var service = new InMemoryIndexService();
        Map<String, Object> docA = new HashMap<>(Map.of("id", "a1", "title", "Doc A"));
        Map<String, Object> docB = new HashMap<>(Map.of("id", "b2", "title", "Doc B"));
        Map<String, Object> docC = new HashMap<>(Map.of("title", "Doc C"));
        var docs = List.of(docA, docB, docC);

        service.indexBatch(docs);

        assertEquals(3, service.size());
        assertNotNull(service.get("a1"));
        assertNotNull(service.get("b2"));
        assertEquals("Doc A", service.get("a1").get("title"));
    }

    @Test
    void indexBatchGeneratesUuidForMissingId() {
        var service = new InMemoryIndexService();
        service.indexBatch(List.of(new HashMap<>(Map.of("title", "No ID"))));

        assertEquals(1, service.size());
        // Should have been assigned a UUID — not "null"
        assertNull(service.get("null"));
    }

    @Test
    void getReturnsCopyNotReference() {
        var service = new InMemoryIndexService();
        var doc = new HashMap<String, Object>();
        doc.put("title", "Original");
        service.index("d1", doc);

        doc.put("title", "Mutated");
        var retrieved = service.get("d1");
        assertEquals("Original", retrieved.get("title"));
    }

    @Test
    void searchReturnsDocumentsWithIdField() {
        var service = new InMemoryIndexService();
        service.index("my-id", Map.of("title", "Searchable"));

        var results = service.search("Searchable");
        assertEquals(1, results.size());
        assertEquals("my-id", results.get(0).get("id"));
    }

    // ---- Plugin lifecycle tests ----

    @Test
    void pluginInitializesCorrectly() {
        var plugin = new IndexerPlugin();
        var ctx = createTestContext();

        plugin.onInitialize(ctx);

        assertTrue(ctx.getService(IndexerService.class).isPresent());
        assertEquals(1, ctx.getRegisteredRoutes().size());
        assertEquals("/index", ctx.getRegisteredRoutes().get(0));
    }

    @Test
    void healthCheckRegisteredAndReturnsTrue() {
        var plugin = new IndexerPlugin();
        var ctx = createTestContext();

        plugin.onInitialize(ctx);

        var healthChecks = ctx.getHealthChecks();
        assertTrue(healthChecks.containsKey("indexer"));
        assertTrue(healthChecks.get("indexer").get());
    }

    // ---- processAndRelay backward compat ----

    @Test
    void pluginMetadataUnchanged() {
        var plugin = new IndexerPlugin();
        assertEquals("dev.vatn.plugins.indexer", plugin.getId());
        assertEquals("VATN Indexer Plugin", plugin.getName());
    }

    private static VNodeContextImpl createTestContext() {
        var json = new VJsonImpl();
        var firewall = new VFirewallImpl();
        Map<Class<?>, Object> services = new HashMap<>();
        services.put(VJson.class, json);
        return new VNodeContextImpl("test-node", firewall, services);
    }
}
