package dev.vatn.plugins.mongodb;

public final class MongoConfig {
    private final String connectionString;
    private final String database;
    private final int minPoolSize;
    private final int maxPoolSize;
    private final int maxIdleTimeMs;

    private static final int DEFAULT_MIN_POOL_SIZE = 0;
    private static final int DEFAULT_MAX_POOL_SIZE = 100;
    private static final int DEFAULT_MAX_IDLE_TIME_MS = 0;

    private MongoConfig(String connectionString, String database,
                        int minPoolSize, int maxPoolSize, int maxIdleTimeMs) {
        this.connectionString = connectionString;
        this.database = database;
        this.minPoolSize = minPoolSize;
        this.maxPoolSize = maxPoolSize;
        this.maxIdleTimeMs = maxIdleTimeMs;
    }

    public static MongoConfig of(String connectionString, String database) {
        return new MongoConfig(connectionString, database,
                DEFAULT_MIN_POOL_SIZE, DEFAULT_MAX_POOL_SIZE, DEFAULT_MAX_IDLE_TIME_MS);
    }

    public static MongoConfig localhost(String database) {
        return of("mongodb://localhost:27017", database);
    }

    public MongoConfig withMinPoolSize(int minPoolSize) {
        return new MongoConfig(connectionString, database, minPoolSize, maxPoolSize, maxIdleTimeMs);
    }

    public MongoConfig withMaxPoolSize(int maxPoolSize) {
        return new MongoConfig(connectionString, database, minPoolSize, maxPoolSize, maxIdleTimeMs);
    }

    public MongoConfig withMaxIdleTimeMs(int maxIdleTimeMs) {
        return new MongoConfig(connectionString, database, minPoolSize, maxPoolSize, maxIdleTimeMs);
    }

    public String getConnectionString() { return connectionString; }
    public String getDatabase()         { return database; }
    public int getMinPoolSize()         { return minPoolSize; }
    public int getMaxPoolSize()         { return maxPoolSize; }
    public int getMaxIdleTimeMs()       { return maxIdleTimeMs; }
}
