package dev.vatn.plugins.postgres;

/**
 * Connection configuration for the PostgreSQL plugin.
 *
 * <pre>{@code
 * PostgresConfig config = PostgresConfig
 *     .of("localhost", 5432, "mydb", "user", "secret")
 *     .withPoolSize(10);
 * }</pre>
 */
public final class PostgresConfig {

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final int connectionTimeoutMs;
    private final boolean autoCommit;

    private PostgresConfig(String host, int port, String database, String username,
                           String password, int poolSize, int connectionTimeoutMs, boolean autoCommit) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.autoCommit = autoCommit;
    }

    public static PostgresConfig of(String host, int port, String database,
                                    String username, String password) {
        return new PostgresConfig(host, port, database, username, password, 10, 30_000, true);
    }

    public PostgresConfig withPoolSize(int poolSize) {
        return new PostgresConfig(host, port, database, username, password,
                poolSize, connectionTimeoutMs, autoCommit);
    }

    public PostgresConfig withConnectionTimeoutMs(int ms) {
        return new PostgresConfig(host, port, database, username, password,
                poolSize, ms, autoCommit);
    }

    public PostgresConfig withAutoCommit(boolean autoCommit) {
        return new PostgresConfig(host, port, database, username, password,
                poolSize, connectionTimeoutMs, autoCommit);
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
}
