# vatn-plugin-postgres
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Provides a HikariCP-managed PostgreSQL connection pool as a VATN service.

## How it works

Initialises a HikariCP `DataSource` at plugin startup using the supplied connection parameters. A `postgres` health check is registered with the VATN node via `ctx.registerHealthCheck`, so the admin dashboard and health endpoints reflect database reachability. `DataSourceService` exposes both the raw `HikariDataSource` for framework integration and a `getConnection()` shortcut for direct JDBC use. The pool is closed cleanly on node shutdown.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-postgres</artifactId>
    <version>1.0-alpha.15</version>
</dependency>
```

```java
PostgresConfig config = PostgresConfig.of("localhost", 5432, "mydb", "app", "secret")
    .withPoolSize(10)
    .withSchema("public")
    .withSsl(true);

VNodeRunner.create()
    .addPlugin(new PostgresPlugin(config))
    .run();
```

## API

```java
public interface DataSourceService {
    HikariDataSource dataSource();
    Connection       getConnection() throws SQLException;
}
```

```java
DataSourceService ds = ctx.service(DataSourceService.class);

// Direct JDBC
try (Connection conn = ds.getConnection();
     PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM users WHERE id = ?")) {
    ps.setLong(1, userId);
    try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) System.out.println(rs.getString("name"));
    }
}

// Framework integration (e.g. jOOQ, JDBI)
DSLContext jooq = DSL.using(ds.dataSource(), SQLDialect.POSTGRES);
```

## Configuration

| Option       | Default    | Meaning                                              |
|--------------|------------|------------------------------------------------------|
| `host`       | —          | PostgreSQL server hostname                           |
| `port`       | —          | PostgreSQL server port (typically 5432)              |
| `database`   | —          | Database name                                        |
| `username`   | —          | Authentication username                              |
| `password`   | —          | Authentication password                              |
| `poolSize`   | HikariCP default (10) | Maximum number of connections in the pool |
| `schema`     | not set    | Default schema (`search_path`)                       |
| `ssl`        | `false`    | Enable SSL/TLS for the JDBC connection               |

## Notes

- Connections borrowed from the pool must be closed (try-with-resources) to return them to the pool; leaking connections will exhaust the pool.
- The registered `postgres` health check executes a lightweight `SELECT 1` against the pool.
- `dataSource()` returns the `HikariDataSource` directly, which exposes pool statistics via `getHikariPoolMXBean()`.
- SSL mode uses the PostgreSQL JDBC driver's default `verify-full` when `withSsl(true)` is set; supply a truststore via JVM system properties if your CA is not in the default truststore.
