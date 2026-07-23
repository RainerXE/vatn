package dev.vatn.plugins.fts;

/**
 * A single ranked full-text search hit.
 *
 * @param docId      the application document id supplied at index time
 * @param collection the logical collection the document belongs to
 * @param score      relevance score (higher is more relevant; derived from BM25)
 * @param snippet    a highlighted excerpt of the matching text, or null if none was requested
 */
public record FtsResult(String docId, String collection, double score, String snippet) {}
