package dev.vatn.plugins.mongodb;

public final class MongoConfig {
    private final String connectionString;
    private final String database;

    private MongoConfig(String connectionString, String database) {
        this.connectionString = connectionString;
        this.database = database;
    }

    public static MongoConfig of(String connectionString, String database) {
        return new MongoConfig(connectionString, database);
    }
    public static MongoConfig localhost(String database) {
        return of("mongodb://localhost:27017", database);
    }

    public String getConnectionString() { return connectionString; }
    public String getDatabase()         { return database; }
}
