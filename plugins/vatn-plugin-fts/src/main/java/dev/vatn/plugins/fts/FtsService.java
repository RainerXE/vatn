package dev.vatn.plugins.fts;

import dev.vatn.api.VService;

import java.util.List;

/**
 * Full-text search service backed by SQLite FTS5.
 *
 * <p>Documents are grouped into <em>collections</em> (e.g. {@code "books"}, {@code "notes"}) and
 * indexed by an application-chosen {@code docId}. Queries use FTS5 syntax — terms, {@code "exact
 * phrases"}, prefix ({@code foo*}), boolean ({@code a AND b}, {@code a OR b}, {@code NOT}), and
 * column filters ({@code title:foo}). Results are ranked by BM25 and can include highlighted
 * snippets.
 *
 * <pre>{@code
 * FtsService fts = ctx.getService(FtsService.class).orElseThrow();
 * fts.index("books", "book-42", "The Left Hand of Darkness", "Ursula K. Le Guin — science fiction…");
 *
 * List<FtsResult> hits = fts.search("darkness OR winter", 20);
 * List<FtsResult> inBooks = fts.search("books", "title:darkness", 20);
 * }</pre>
 */
public interface FtsService extends VService {

    /**
     * Indexes (or re-indexes) a document. Re-indexing the same {@code docId} replaces the previous
     * entry.
     *
     * @param collection logical collection name
     * @param docId      application document id
     * @param title      the title field (weighted higher in ranking)
     * @param body       the body / content field
     */
    void index(String collection, String docId, String title, String body);

    /** Removes a document from the index. No-op if absent. */
    void delete(String collection, String docId);

    /** Removes every document in a collection from the index. */
    void clear(String collection);

    /**
     * Searches across all collections.
     *
     * @param query FTS5 match expression
     * @param limit maximum hits to return
     */
    List<FtsResult> search(String query, int limit);

    /** Searches within a single collection. */
    List<FtsResult> search(String collection, String query, int limit);

    /** Total number of indexed documents (optionally within a collection; null = all). */
    long count(String collection);
}
