package com.qswitch.settlement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SettlementService {

    private final DataSource dataSource;

    public SettlementService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public SettlementBatchSummary runSettlement() {
        String batchId = UUID.randomUUID().toString();

        String selectSql = """
            SELECT id, amount
            FROM transactions
            WHERE status IN ('AUTHORIZED', 'APPROVED')
              AND settled = FALSE
            ORDER BY id
            FOR UPDATE
        """;

        String updateSql = """
            UPDATE transactions
            SET settled = TRUE,
                settlement_date = CURRENT_DATE,
                batch_id = ?
            WHERE id = ?
        """;

        long totalAmount = 0L;
        int count = 0;

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement(selectSql);
                 PreparedStatement update = connection.prepareStatement(updateSql);
                 ResultSet rs = select.executeQuery()) {

                while (rs.next()) {
                    long id = rs.getLong("id");
                    long amount = rs.getLong("amount");

                    totalAmount += amount;
                    count++;

                    update.setString(1, batchId);
                    update.setLong(2, id);
                    update.addBatch();
                }

                if (count > 0) {
                    update.executeBatch();
                }

                saveBatch(connection, batchId, count, totalAmount);
                connection.commit();

                SettlementBatchSummary summary = new SettlementBatchSummary(
                    batchId,
                    count,
                    totalAmount,
                    LocalDate.now()
                );

                System.out.println("Settlement completed: count=" + count + " totalAmount=" + totalAmount + " batchId=" + batchId);
                return summary;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw new RuntimeException("Settlement execution failed", e);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Settlement execution failed", e);
        }
    }

    public List<NetPosition> getNetPositions() {
        String sql = """
            SELECT terminal_id, SUM(amount) AS net_amount
            FROM transactions
            WHERE settled = TRUE
            GROUP BY terminal_id
            ORDER BY terminal_id
        """;

        List<NetPosition> positions = new ArrayList<>();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                positions.add(new NetPosition(
                    rs.getString("terminal_id"),
                    rs.getLong("net_amount")
                ));
            }
            return positions;
        } catch (SQLException e) {
            throw new RuntimeException("Net position query failed", e);
        }
    }

    private void saveBatch(Connection connection, String batchId, int count, long amount) throws SQLException {
        String sql = """
            INSERT INTO settlement_batches (batch_id, total_count, total_amount)
            VALUES (?, ?, ?)
        """;

        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.NO_GENERATED_KEYS)) {
            ps.setString(1, batchId);
            ps.setInt(2, count);
            ps.setLong(3, amount);
            ps.executeUpdate();
        }
    }

    public static final class SettlementBatchSummary {
        private final String batchId;
        private final int totalCount;
        private final long totalAmount;
        private final LocalDate settlementDate;

        public SettlementBatchSummary(String batchId, int totalCount, long totalAmount, LocalDate settlementDate) {
            this.batchId = batchId;
            this.totalCount = totalCount;
            this.totalAmount = totalAmount;
            this.settlementDate = settlementDate;
        }

        public String getBatchId() {
            return batchId;
        }

        public int getTotalCount() {
            return totalCount;
        }

        public long getTotalAmount() {
            return totalAmount;
        }

        public LocalDate getSettlementDate() {
            return settlementDate;
        }
    }

    public static final class NetPosition {
        private final String terminalId;
        private final long netAmount;

        public NetPosition(String terminalId, long netAmount) {
            this.terminalId = terminalId;
            this.netAmount = netAmount;
        }

        public String getTerminalId() {
            return terminalId;
        }

        public long getNetAmount() {
            return netAmount;
        }
    }
}
