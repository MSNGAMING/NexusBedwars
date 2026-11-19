package dev.nexus.bedwars.db;

import java.util.Locale;

/** SQL dialects we support. Everything else in the schema is deliberately portable ANSI SQL. */
public enum Dialect {
    SQLITE("org.sqlite.JDBC", 0),
    MYSQL("com.mysql.cj.jdbc.Driver", 3306),
    POSTGRESQL("org.postgresql.Driver", 5432);

    private final String driver;
    private final int defaultPort;

    Dialect(String driver, int defaultPort) {
        this.driver = driver;
        this.defaultPort = defaultPort;
    }

    public String driver() { return driver; }
    public int defaultPort() { return defaultPort; }

    /** Auto-incrementing 64-bit primary key column definition. */
    public String autoIncrementPk() {
        return switch (this) {
            case SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT";
            case MYSQL -> "BIGINT AUTO_INCREMENT PRIMARY KEY";
            case POSTGRESQL -> "BIGSERIAL PRIMARY KEY";
        };
    }

    public static Dialect parse(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "MYSQL", "MARIADB" -> MYSQL;
            case "POSTGRES", "POSTGRESQL", "PGSQL" -> POSTGRESQL;
            default -> SQLITE;
        };
    }
}
