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

        String sql = "INSERT INTO transaction_meta (stan, acquirer_id, issuer_id, processing_code) VALUES (?, ?, ?, ?)";
        try (Connection connection = DatabaseSupport.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
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