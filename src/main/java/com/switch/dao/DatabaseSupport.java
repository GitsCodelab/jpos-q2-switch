package com.qswitch.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

final class DatabaseSupport {
    private static final String DB_PERSISTENCE_ENABLED = "DB_PERSISTENCE_ENABLED";
    private static final String DEFAULT_DB_HOST = "localhost";
    private static final String DEFAULT_DB_PORT = "5432";
    private static final String DEFAULT_DB_NAME = "jpos";
    private static final String DEFAULT_DB_USER = "postgres";
    private static final String DEFAULT_DB_PASSWORD = "postgres";

    private DatabaseSupport() {
    }

    static boolean isJdbcEnabled() {
        String enabled = getEnv(DB_PERSISTENCE_ENABLED);
        if (enabled == null || enabled.isBlank()) {
            return true;
        }
        return Boolean.parseBoolean(enabled);
    }

    static Connection getConnection() throws SQLException {
        String jdbcUrl = getJdbcUrl();
        String user = getEnvOrDefault("DB_USER", DEFAULT_DB_USER);
        String password = getEnvOrDefault("DB_PASSWORD", DEFAULT_DB_PASSWORD);
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private static String getJdbcUrl() {
        String explicit = getEnv("DB_URL");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String host = getEnvOrDefault("DB_HOST", DEFAULT_DB_HOST);
        String port = getEnvOrDefault("DB_PORT", DEFAULT_DB_PORT);
        String dbName = getEnvOrDefault("DB_NAME", DEFAULT_DB_NAME);
        return "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = getEnv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String getEnv(String key) {
        return System.getenv(key);
    }
}