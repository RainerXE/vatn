package dev.vatn.bench.db;

import dev.vatn.api.VPersistenceService;
import dev.vatn.bench.VNodeState;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.sql.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VPersistenceService (SQLite) throughput: single insert, batched insert,
 * primary-key lookup, index scan.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class PersistenceBench extends VNodeState {

    private VPersistenceService db;
    private final AtomicInteger counter = new AtomicInteger(0);

    @Setup(Level.Trial)
    @Override
    public void startNode() throws Exception {
        super.startNode();
        db = context.getService(VPersistenceService.class).orElseThrow();
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS bench_kv (id TEXT PRIMARY KEY, val TEXT, num INTEGER)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_bench_num ON bench_kv(num)");
            // Pre-populate 1000 rows so reads have data
            for (int i = 0; i < 1000; i++) {
                s.execute("INSERT OR IGNORE INTO bench_kv VALUES ('seed_" + i + "', 'val_" + i + "', " + i + ")");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void insert_single() throws SQLException {
        int id = counter.incrementAndGet();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO bench_kv VALUES (?, ?, ?)")) {
            ps.setString(1, "k_" + id);
            ps.setString(2, "v_" + id);
            ps.setInt(3, id);
            ps.executeUpdate();
        }
    }

    @Benchmark
    public void insert_batch_100() throws SQLException {
        int base = counter.addAndGet(100);
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO bench_kv VALUES (?, ?, ?)")) {
                for (int i = base; i < base + 100; i++) {
                    ps.setString(1, "b_" + i);
                    ps.setString(2, "v_" + i);
                    ps.setInt(3, i);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            c.setAutoCommit(true);
        }
    }

    @Benchmark
    public String read_by_pk(Blackhole bh) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT val FROM bench_kv WHERE id = ?")) {
            ps.setString(1, "seed_42");
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Benchmark
    public int read_by_index_scan(Blackhole bh) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM bench_kv WHERE num > ? AND num < ?")) {
            ps.setInt(1, 100);
            ps.setInt(2, 200);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
