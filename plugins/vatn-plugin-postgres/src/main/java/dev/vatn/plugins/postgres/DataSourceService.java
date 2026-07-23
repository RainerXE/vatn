package dev.vatn.plugins.postgres;

import dev.vatn.api.VService;

import javax.sql.DataSource;

/**
 * VATN service wrapper for a JDBC {@link DataSource}.
 * Registered by {@link PostgresPlugin} so other plugins can obtain the
 * connection pool via the standard VATN service registry.
 *
 * <pre>{@code
 * DataSource ds = ctx.getService(DataSourceService.class)
 *                    .orElseThrow()
 *                    .dataSource();
 * try (var conn = ds.getConnection()) { ... }
 * }</pre>
 */
public interface DataSourceService extends VService {
    DataSource dataSource();
}
