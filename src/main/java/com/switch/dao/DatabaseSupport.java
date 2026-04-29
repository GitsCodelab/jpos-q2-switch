package com.qswitch.dao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public final class DatabaseSupport {
    private static final String DB_PERSISTENCE_ENABLED = "DB_PERSISTENCE_ENABLED";
    private static final String DB_IN_MEMORY_MIRROR = "DB_IN_MEMORY_MIRROR";
    private static final String DB_POOL_MAX_SIZE = "DB_POOL_MAX_SIZE";
    private static final String DB_CONNECT_RETRIES = "DB_CONNECT_RETRIES";
    private static final String DB_CONNECT_RETRY_DELAY_MS = "DB_CONNECT_RETRY_DELAY_MS";

    private static final String DEFAULT_DB_HOST = "localhost";
    private static final String DEFAULT_DB_PORT = "5432";
    private static final String DEFAULT_DB_NAME = "jpos";
    private static final String DEFAULT_DB_USER = "postgres";
    private static final String DEFAULT_DB_PASSWORD = "postgres";
    private static final int DEFAULT_POOL_MAX_SIZE = 10;
    private static final int DEFAULT_CONNECT_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 150L;

    private static volatile HikariDataSource dataSource;

    private DatabaseSupport() {
    }

    public static boolean isJdbcEnabled() {
        String enabled = getEnv(DB_PERSISTENCE_ENABLED);
        if (enabled == null || enabled.isBlank()) {
            return true;
        }
        return Boolean.parseBoolean(enabled);
    }

    public static boolean isInMemoryMirrorEnabled() {
        String mirror = getEnv(DB_IN_MEMORY_MIRROR);
        return mirror != null && Boolean.parseBoolean(mirror);
    }

    public static Connection getConnection() throws SQLException {
        if (!isJdbcEnabled()) {
            throw new IllegalStateException("JDBC persistence is disabled");
        }
        SQLException last = null;
        int retries = getIntEnvOrDefault(DB_CONNECT_RETRIES, DEFAULT_CONNECT_RETRIES);
        long retryDelayMs = getLongEnvOrDefault(DB_CONNECT_RETRY_DELAY_MS, DEFAULT_RETRY_DELAY_MS);
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                return getDataSource().getConnection();
            } catch (SQLException e) {
                last = e;
                if (attempt < retries) {
                    sleepQuietly(retryDelayMs);
                }
            }
        }
        throw last;
    }

    private static HikariDataSource getDataSource() {
        if (dataSource == null) {
            synchronized (DatabaseSupport.class) {
                if (dataSource == null) {
                    HikariConfig cfg = new HikariConfig();
                    cfg.setJdbcUrl(getJdbcUrl());
                    cfg.setUsername(getEnvOrDefault("DB_USER", DEFAULT_DB_USER));
                    cfg.setPassword(getEnvOrDefault("DB_PASSWORD", DEFAULT_DB_PASSWORD));
                    cfg.setMaximumPoolSize(getIntEnvOrDefault(DB_POOL_MAX_SIZE, DEFAULT_POOL_MAX_SIZE));
                    cfg.setMinimumIdle(1);
                    cfg.setPoolName("switch-db-pool");
                    cfg.setAutoCommit(true);
                    cfg.setConnectionTimeout(5000);
                    cfg.setValidationTimeout(3000);
                    cfg.setInitializationFailTimeout(-1);
                    dataSource = new HikariDataSource(cfg);
                }
            }
        }
        return dataSource;
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

    private static int getIntEnvOrDefault(String key, int defaultValue) {
        String value = getEnv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long getLongEnvOrDefault(String key, long defaultValue) {
        String value = getEnv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}