package com.qswitch.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class TransactionMetaDAO {
    private final boolean jdbcEnabled = DatabaseSupport.isJdbcEnabled();

    public void saveMeta(String stan, String acquirerId, String issuerId, String processingCode) {
        if (!jdbcEnabled) {
            return;
        }

        try (Connection connection = DatabaseSupport.getConnection()) {
            saveMeta(connection, stan, acquirerId, issuerId, processingCode);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist transaction meta", e);
        }
    }

    public void saveMeta(Connection connection, String stan, String acquirerId, String issuerId, String processingCode) {
        if (!jdbcEnabled) {
            return;
        }

        String sql = "INSERT INTO transaction_meta (stan, acquirer_id, issuer_id, processing_code) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (stan) DO UPDATE SET "
            + "acquirer_id=EXCLUDED.acquirer_id, issuer_id=EXCLUDED.issuer_id, processing_code=EXCLUDED.processing_code";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, stan);
            ps.setString(2, acquirerId);
            ps.setString(3, issuerId);
            ps.setString(4, processingCode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist transaction meta", e);
        }
    }
}