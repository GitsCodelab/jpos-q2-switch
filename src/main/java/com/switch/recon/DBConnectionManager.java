package com.qswitch.recon;

import com.qswitch.dao.DatabaseSupport;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

public final class DBConnectionManager {
    private static final DataSource DATA_SOURCE = new DatabaseSupportDataSource();

    private DBConnectionManager() {
    }

    public static DataSource getDataSource() {
        return DATA_SOURCE;
    }

    private static final class DatabaseSupportDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return DatabaseSupport.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("Custom credentials are not supported");
        }

        @Override
        public PrintWriter getLogWriter() {
            return DriverManager.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            DriverManager.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("com.qswitch.recon.DBConnectionManager");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Cannot unwrap to " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
