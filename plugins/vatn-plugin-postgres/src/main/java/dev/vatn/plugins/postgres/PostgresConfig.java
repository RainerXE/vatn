package dev.vatn.plugins.postgres;

public final class PostgresConfig {

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final int connectionTimeoutMs;
    private final boolean autoCommit;
    private final int idleTimeoutMs;
    private final int maxLifetimeMs;
    private final int minimumIdle;
    private final int leakDetectionThresholdMs;

    private PostgresConfig(String host, int port, String database, String username,
                           String password, int poolSize, int connectionTimeoutMs,
                           boolean autoCommit, int idleTimeoutMs, int maxLifetimeMs,
                           int minimumIdle, int leakDetectionThresholdMs) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.autoCommit = autoCommit;
        this.idleTimeoutMs = idleTimeoutMs;
        this.maxLifetimeMs = maxLifetimeMs;
        this.minimumIdle = minimumIdle;
        this.leakDetectionThresholdMs = leakDetectionThresholdMs;
    }

    public static PostgresConfig of(String host, int port, String database,
                                    String username, String password) {
        return new PostgresConfig(host, port, database, username, password,
                10, 30_000, true, 600_000, 1_800_000, 10, 0);
    }

    public PostgresConfig withPoolSize(int poolSize) {
        return new PostgresConfig(host, port, database, username, password,
                poolSize, connectionTimeoutMs, autoCommit,
                idleTimeoutMs, maxLifetimeMs, poolSize, leakDetectionThresholdMs);
    }

    public PostgresConfig withConnectionTimeoutMs(int ms) {
        return new PostgresConfig(host, port, database, username, password,
                poolSize, ms, autoCommit,
                idleTimeoutMs, maxLifetimeMs, minimumIdle, leakDetectionThresholdMs);
    }

    public PostgresConfig withAutoCommit(boolean autoCommit) {
        return new PostgresConfig(host, port, database, username, password,
                poolSize, connectionTimeoutMs, autoCommit,
                idleTimeoutMs, maxLifetimeMs, minimumIdle, leakDetectionThresholdMs);
    }

    public PostgresConfig withIdleTimeoutMs(int ms) {
        return new PostgresConfig(host, port, database, username, password,
                poolSize, connectionTimeoutMs, autoCommit,
                ms, maxLifetimeMs, minimumIdle, leakDetectionThresholdMs);
    }

    public PostgresConfig withMaxLifetimeMs(int ms) {
        return new PostgresConfig(host, port, database, username, password,
                poolSize, connectionTimeoutMs, autoCommit,
                idleTimeoutMs, ms, minimumIdle, leakDetectionThresholdMs);
    }

    public PostgresConfig withMinimumIdle(int min) {
        return new PostgresConfig(host, port, database, username, password,
                poolSize, connectionTimeoutMs, autoCommit,
                idleTimeoutMs, maxLifetimeMs, min, leakDetectionThresholdMs);
    }

    public PostgresConfig withLeakDetectionThresholdMs(int ms) {
        return new PostgresConfig(host, port, database, username, password,
                poolSize, connectionTimeoutMs, autoCommit,
                idleTimeoutMs, maxLifetimeMs, minimumIdle, ms);
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    public String getHost()               { return host; }
    public int getPort()                  { return port; }
    public String getDatabase()           { return database; }
    public String getUsername()           { return username; }
    public String getPassword()           { return password; }
    public int getPoolSize()              { return poolSize; }
    public int getConnectionTimeoutMs()   { return connectionTimeoutMs; }
    public boolean isAutoCommit()         { return autoCommit; }
    public int getIdleTimeoutMs()         { return idleTimeoutMs; }
    public int getMaxLifetimeMs()         { return maxLifetimeMs; }
    public int getMinimumIdle()           { return minimumIdle; }
    public int getLeakDetectionThresholdMs() { return leakDetectionThresholdMs; }
}
