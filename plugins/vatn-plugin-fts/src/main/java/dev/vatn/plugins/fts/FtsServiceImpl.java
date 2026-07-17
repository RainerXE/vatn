package dev.vatn.plugins.fts;

import dev.vatn.api.VPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite FTS5 implementation of {@link FtsService}.
 *
 * <p>Backed by a single FTS5 virtual table ({@code vatn_fts}) in the node's database, with
 * {@code porter unicode61} tokenisation (stemming + Unicode folding) and BM25 ranking. The
 * {@code title} column is boosted at query time so title matches outrank body matches.
 */
public class FtsServiceImpl implements FtsService {

    private static final Logger log = LoggerFactory.getLogger(FtsServiceImpl.class);

    private final VPersistenceService db;

    public FtsServiceImpl(VPersistenceService db) {
        this.db = db;
        createSchema();
    }

    private void createSchema() {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            // doc_id + collection are unindexed metadata columns; title/body are searchable.
            s.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS vatn_fts USING fts5(
                    doc_id UNINDEXED,
                    collection UNINDEXED,
                    title,
                    body,
                    tokenize = 'porter unicode61'
                )
                """);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create FTS5 table — does this SQLite build include FTS5?", e);
        }
    }

    @Override
    public void index(String collection, String docId, String title, String body) {
        // FTS5 has no UPSERT; delete the prior row for this id then insert.
        try (Connection c = db.getConnection()) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                deleteRow(c, collection, docId);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vatn_fts(doc_id, collection, title, body) VALUES(?,?,?,?)")) {
                    ps.setString(1, docId);
                    ps.setString(2, collection);
                    ps.setString(3, title != null ? title : "");
                    ps.setString(4, body != null ? body : "");
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            throw new RuntimeException("FTS index failed for " + collection + "/" + docId, e);
        }
    }

    @Override
    public void delete(String collection, String docId) {
        try (Connection c = db.getConnection()) {
            deleteRow(c, collection, docId);
        } catch (SQLException e) {
            throw new RuntimeException("FTS delete failed for " + collection + "/" + docId, e);
        }
    }

    private void deleteRow(Connection c, String collection, String docId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM vatn_fts WHERE doc_id=? AND collection=?")) {
            ps.setString(1, docId);
            ps.setString(2, collection);
            ps.executeUpdate();
        }
    }

    @Override
    public void clear(String collection) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM vatn_fts WHERE collection=?")) {
            ps.setString(1, collection);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("FTS clear failed for " + collection, e);
        }
    }

    @Override
    public List<FtsResult> search(String query, int limit) {
        return doSearch(null, query, limit);
    }

    @Override
    public List<FtsResult> search(String collection, String query, int limit) {
        return doSearch(collection, query, limit);
    }

    private List<FtsResult> doSearch(String collection, String query, int limit) {
        // bm25(table, titleWeight, bodyWeight): lower (more negative) is a better match.
        // Negate so the returned score reads "higher = better".
        StringBuilder sql = new StringBuilder("""
            SELECT doc_id, collection,
                   bm25(vatn_fts, 10.0, 1.0) AS rank,
                   snippet(vatn_fts, 3, '[', ']', '…', 12) AS snip
            FROM vatn_fts
            WHERE vatn_fts MATCH ?
            """);
        if (collection != null) sql.append(" AND collection = ?");
        sql.append(" ORDER BY rank ASC LIMIT ?");

        List<FtsResult> results = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, query);
            if (collection != null) ps.setString(i++, collection);
            ps.setInt(i, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new FtsResult(
                            rs.getString("doc_id"),
                            rs.getString("collection"),
                            -rs.getDouble("rank"),
                            rs.getString("snip")));
                }
            }
        } catch (SQLException e) {
            // A malformed FTS5 query (e.g. unbalanced quotes) surfaces as a SQLException; treat as no hits.
            log.debug("[FTS] query failed: {}", query, e);
            return List.of();
        }
        return results;
    }

    @Override
    public long count(String collection) {
        String sql = collection == null
                ? "SELECT COUNT(*) FROM vatn_fts"
                : "SELECT COUNT(*) FROM vatn_fts WHERE collection=?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (collection != null) ps.setString(1, collection);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new RuntimeException("FTS count failed", e);
        }
    }
}
