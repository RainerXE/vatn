package dev.vatn.plugins.postgres;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

public class DataSourceServiceImpl implements DataSourceService {
    private final HikariDataSource dataSource;

    public DataSourceServiceImpl(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public DataSource dataSource() { return dataSource; }

    public void close() {
        if (!dataSource.isClosed()) dataSource.close();
    }
}
