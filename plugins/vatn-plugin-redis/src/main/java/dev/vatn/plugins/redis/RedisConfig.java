package dev.vatn.plugins.redis;

public final class RedisConfig {
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int poolSize;
    private final int timeoutMs;

    private RedisConfig(String host, int port, String password, int database, int poolSize, int timeoutMs) {
        this.host = host; this.port = port; this.password = password;
        this.database = database; this.poolSize = poolSize; this.timeoutMs = timeoutMs;
    }

    public static RedisConfig of(String host, int port) {
        return new RedisConfig(host, port, null, 0, 10, 2000);
    }
    public static RedisConfig localhost() { return of("localhost", 6379); }

    public RedisConfig withPassword(String password)  { return new RedisConfig(host, port, password, database, poolSize, timeoutMs); }
    public RedisConfig withDatabase(int database)     { return new RedisConfig(host, port, password, database, poolSize, timeoutMs); }
    public RedisConfig withPoolSize(int poolSize)     { return new RedisConfig(host, port, password, database, poolSize, timeoutMs); }
    public RedisConfig withTimeoutMs(int timeoutMs)   { return new RedisConfig(host, port, password, database, poolSize, timeoutMs); }

    public String getHost()     { return host; }
    public int getPort()        { return port; }
    public String getPassword() { return password; }
    public int getDatabase()    { return database; }
    public int getPoolSize()    { return poolSize; }
    public int getTimeoutMs()   { return timeoutMs; }
}
