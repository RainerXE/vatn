package dev.vatn.plugins.fts;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.VSchemaContributor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FtsServiceTest {

    @TempDir Path tempDir;
    private FtsService fts;

    @BeforeEach
    void setUp() throws Exception {
        // Use file-based DB so a fresh connection per call still shares the same data.
        String url = "jdbc:sqlite:" + tempDir.resolve("fts-test.db").toAbsolutePath();
        // Prime the schema with one connection, then let the service open its own per-call.
        VPersistenceService db = new VPersistenceService() {
            @Override public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url);
            }
            @Override public void registerSchemaContributor(VSchemaContributor c) {
                try (Connection conn = DriverManager.getConnection(url);
                     Statement s = conn.createStatement()) {
                    c.contribute(s);
                } catch (SQLException e) { throw new RuntimeException(e); }
            }
        };
        fts = new FtsServiceImpl(db);
    }

    @Test
    void indexAndSearchBasicTerm() {
        fts.index("books", "b1", "The Left Hand of Darkness", "Le Guin science fiction");
        fts.index("books", "b2", "Foundation", "Asimov future history galactic");

        List<FtsResult> hits = fts.search("darkness", 10);
        assertEquals(1, hits.size());
        assertEquals("b1", hits.get(0).docId());
    }

    @Test
    void searchAcrossCollections() {
        fts.index("books",  "b1", "Dune",       "desert planet spice");
        fts.index("notes",  "n1", "Dune notes", "my reading notes");

        List<FtsResult> all = fts.search("dune", 10);
        assertEquals(2, all.size());
    }

    @Test
    void searchWithinCollection() {
        fts.index("books", "b1", "Dune", "desert planet");
        fts.index("notes", "n1", "Dune notes", "notes");

        List<FtsResult> hits = fts.search("books", "dune", 10);
        assertEquals(1, hits.size());
        assertEquals("books", hits.get(0).collection());
    }

    @Test
    void reindexReplacesEntry() {
        fts.index("col", "d1", "Old Title", "old body");
        fts.index("col", "d1", "New Title", "new body"); // same docId

        List<FtsResult> byOld = fts.search("col", "old", 10);
        List<FtsResult> byNew = fts.search("col", "new", 10);

        assertTrue(byOld.isEmpty(), "old content must be replaced");
        assertEquals(1, byNew.size());
    }

    @Test
    void deleteRemovesDocument() {
        fts.index("col", "d1", "Removable", "gone");
        fts.delete("col", "d1");

        assertTrue(fts.search("removable", 10).isEmpty());
        assertEquals(0, fts.count("col"));
    }

    @Test
    void clearEmptiesCollection() {
        fts.index("col", "d1", "A", "body");
        fts.index("col", "d2", "B", "body");
        fts.clear("col");

        assertEquals(0, fts.count("col"));
        assertTrue(fts.search("col", "body", 10).isEmpty());
    }

    @Test
    void countReturnsCorrectNumbers() {
        fts.index("books", "b1", "One",   "a");
        fts.index("books", "b2", "Two",   "b");
        fts.index("notes", "n1", "Three", "c");

        assertEquals(2, fts.count("books"));
        assertEquals(1, fts.count("notes"));
        assertEquals(3, fts.count(null)); // all collections
    }

    @Test
    void resultsRankedByRelevance() {
        fts.index("col", "exact",   "darkness",         "");
        fts.index("col", "mention", "other title",      "darkness mentioned in body once");
        fts.index("col", "many",    "darkness darkness", "darkness all over");

        List<FtsResult> hits = fts.search("darkness", 10);
        assertFalse(hits.isEmpty());
        // first hit should have highest score (BM25 — higher = better after negation)
        double topScore = hits.get(0).score();
        for (FtsResult r : hits) {
            assertTrue(r.score() <= topScore + 0.001, "results must be in descending score order");
        }
    }

    @Test
    void malformedQueryReturnsEmptyNotException() {
        fts.index("col", "d1", "Title", "body");
        // unbalanced quote — invalid FTS5 syntax
        List<FtsResult> hits = fts.search("\"unclosed", 10);
        assertNotNull(hits);
        assertTrue(hits.isEmpty());
    }

    @Test
    void snippetIsPopulated() {
        fts.index("col", "d1", "Normal Title", "The quick brown fox jumps over the lazy dog");
        List<FtsResult> hits = fts.search("fox", 10);
        assertEquals(1, hits.size());
        assertNotNull(hits.get(0).snippet());
        assertTrue(hits.get(0).snippet().contains("fox") || hits.get(0).snippet().contains("["));
    }

    @Test
    void booleanQueryAndOr() {
        fts.index("col", "d1", "Alpha", "first entry");
        fts.index("col", "d2", "Beta",  "second entry");
        fts.index("col", "d3", "Gamma", "third item");

        List<FtsResult> or  = fts.search("col", "first OR second", 10);
        List<FtsResult> and = fts.search("col", "first AND missing", 10);

        assertEquals(2, or.size());
        assertTrue(and.isEmpty());
    }
}
