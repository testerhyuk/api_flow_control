package com.hyuk.flow_control.support;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

public final class PostgresTestDatabase {
    public static final String IMAGE = "postgres:18.4";

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(IMAGE);

    static {
        CONTAINER.start();
    }

    private PostgresTestDatabase() {
    }

    public static DataSource dataSource() {
        return new DriverManagerDataSource(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    public static void reset(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }
}
