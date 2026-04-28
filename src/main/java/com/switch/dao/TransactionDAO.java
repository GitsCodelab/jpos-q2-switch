package com.qswitch.dao;

import com.qswitch.model.Transaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class TransactionDAO {
    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final boolean jdbcEnabled = DatabaseSupport.isJdbcEnabled();
    private final boolean mirrorInMemory = DatabaseSupport.isInMemoryMirrorEnabled();

    public Transaction save(Transaction transaction) {
        if (jdbcEnabled) {
            try (Connection connection = DatabaseSupport.getConnection()) {
                save(connection, transaction);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to persist transaction", e);
            }
            if (mirrorInMemory) {
                transactions.put(buildKey(transaction.getStan(), transaction.getRrn()), transaction);
            }
            return transaction;
        }
        transactions.put(buildKey(transaction.getStan(), transaction.getRrn()), transaction);
        return transaction;
    }

    public Transaction save(Connection connection, Transaction transaction) {
        if (jdbcEnabled) {
            upsertTransaction(connection, transaction);
        }
        if (!jdbcEnabled || mirrorInMemory) {
            transactions.put(buildKey(transaction.getStan(), transaction.getRrn()), transaction);
        }
        return transaction;
    }

    public Optional<Transaction> findByStanAndRrn(String stan, String rrn) {
        if (jdbcEnabled) {
            Optional<Transaction> fromDb = findByStanAndRrnJdbc(stan, rrn);
            if (fromDb.isPresent()) {
                return fromDb;
            }
        }
        return Optional.ofNullable(transactions.get(buildKey(stan, rrn)));
    }

    public int count() {
        if (jdbcEnabled) {
            return countJdbc();
        }
        return transactions.size();
    }

    public void updateResponse(String stan, String rrn, String rc, String finalStatus) {
        if (!jdbcEnabled) {
            Transaction tx = transactions.get(buildKey(stan, rrn));
            if (tx != null) {
                tx.setResponseCode(rc);
                tx.setFinalStatus(finalStatus);
            }
            return;
        }

        try (Connection connection = DatabaseSupport.getConnection()) {
            updateResponse(connection, stan, rrn, rc, finalStatus);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update transaction response", e);
        }
    }

    public void updateResponse(Connection connection, String stan, String rrn, String rc, String finalStatus) {
        if (!jdbcEnabled) {
            Transaction tx = transactions.get(buildKey(stan, rrn));
            if (tx != null) {
                tx.setResponseCode(rc);
                tx.setFinalStatus(finalStatus);
            }
            return;
        }

        String sql = "UPDATE transactions SET rc=?, final_status=?, updated_at=NOW() "
            + "WHERE stan=? AND rrn=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, rc);
            ps.setString(2, finalStatus);
            ps.setString(3, stan);
            ps.setString(4, rrn);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update transaction response", e);
        }
    }

    private void upsertTransaction(Connection connection, Transaction transaction) {
        String sql = "INSERT INTO transactions (stan, rrn, terminal_id, mti, original_mti, amount, currency, rc, status, final_status, is_reversal, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (stan, rrn) DO UPDATE SET "
            + "terminal_id=EXCLUDED.terminal_id, mti=EXCLUDED.mti, original_mti=EXCLUDED.original_mti, amount=EXCLUDED.amount, "
            + "currency=EXCLUDED.currency, rc=COALESCE(EXCLUDED.rc, transactions.rc), status=EXCLUDED.status, "
            + "final_status=COALESCE(EXCLUDED.final_status, transactions.final_status), is_reversal=EXCLUDED.is_reversal, updated_at=NOW()";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, transaction.getStan());
            ps.setString(2, transaction.getRrn());
            ps.setString(3, transaction.getTerminalId());
            ps.setString(4, transaction.getMti());
            ps.setString(5, transaction.getOriginalMti());
            ps.setLong(6, transaction.getAmount());
            ps.setString(7, transaction.getCurrency());
            ps.setString(8, transaction.getResponseCode());
            ps.setString(9, transaction.getStatus());
            ps.setString(10, transaction.getFinalStatus());
            ps.setBoolean(11, transaction.isReversal());
            ps.setTimestamp(12, Timestamp.from(transaction.getCreatedAt()));
            ps.setTimestamp(13, Timestamp.from(transaction.getCreatedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist transaction", e);
        }
    }

    private Optional<Transaction> findByStanAndRrnJdbc(String stan, String rrn) {
        String sql = "SELECT mti, stan, rrn, amount, currency, rc, created_at, terminal_id, original_mti, status, final_status, is_reversal "
            + "FROM transactions WHERE stan=? AND rrn=? ORDER BY created_at DESC LIMIT 1";
        try (Connection connection = DatabaseSupport.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, stan);
            ps.setString(2, rrn);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Transaction transaction = new Transaction();
                transaction.setMti(rs.getString("mti"));
                transaction.setStan(rs.getString("stan"));
                transaction.setRrn(rs.getString("rrn"));
                transaction.setAmount(rs.getLong("amount"));
                transaction.setCurrency(rs.getString("currency"));
                transaction.setResponseCode(rs.getString("rc"));
                transaction.setTerminalId(rs.getString("terminal_id"));
                transaction.setOriginalMti(rs.getString("original_mti"));
                transaction.setStatus(rs.getString("status"));
                transaction.setFinalStatus(rs.getString("final_status"));
                transaction.setReversal(rs.getBoolean("is_reversal"));
                Timestamp createdAt = rs.getTimestamp("created_at");
                if (createdAt != null) {
                    transaction.setCreatedAt(createdAt.toInstant());
                }
                return Optional.of(transaction);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query transaction", e);
        }
    }

    private int countJdbc() {
        String sql = "SELECT COUNT(*) FROM transactions";
        try (Connection connection = DatabaseSupport.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count transactions", e);
        }
    }

    private String buildKey(String stan, String rrn) {
        return stan + ":" + rrn;
    }
}
