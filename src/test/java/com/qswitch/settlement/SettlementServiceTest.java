package com.qswitch.settlement;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementServiceTest {

    @Test
    void shouldSettleOnlyEligibleTransactionsAndCreateBatch() {
        StubDataSource ds = new StubDataSource();
        ds.addTransaction(1L, "APPROVED", false, "TERM-A", 1000L);
        ds.addTransaction(2L, "AUTHORIZED", false, "TERM-A", 2000L);
        ds.addTransaction(3L, "DECLINED", false, "TERM-B", 3000L);
        ds.addTransaction(4L, "APPROVED", true, "TERM-C", 4000L);

        SettlementService service = new SettlementService(ds);
        SettlementService.SettlementBatchSummary summary = service.runSettlement();

        assertNotNull(summary.getBatchId());
        assertFalse(summary.getBatchId().isBlank());
        assertEquals(2, summary.getTotalCount());
        assertEquals(3000L, summary.getTotalAmount());

        TxRow tx1 = ds.getTx(1L);
        TxRow tx2 = ds.getTx(2L);
        TxRow tx3 = ds.getTx(3L);
        TxRow tx4 = ds.getTx(4L);

        assertTrue(tx1.settled);
        assertTrue(tx2.settled);
        assertFalse(tx3.settled);
        assertTrue(tx4.settled);

        assertEquals(summary.getBatchId(), tx1.batchId);
        assertEquals(summary.getBatchId(), tx2.batchId);
        assertEquals(1, ds.savedBatches.size());
        assertEquals(2, ds.savedBatches.get(0).totalCount);
        assertEquals(3000L, ds.savedBatches.get(0).totalAmount);
    }

    @Test
    void shouldCalculateNetPositionsFromSettledTransactions() {
        StubDataSource ds = new StubDataSource();
        ds.addTransaction(10L, "APPROVED", true, "TERM-A", 1500L);
        ds.addTransaction(11L, "APPROVED", true, "TERM-A", 500L);
        ds.addTransaction(12L, "APPROVED", true, "TERM-B", 900L);
        ds.addTransaction(13L, "APPROVED", false, "TERM-B", 700L);

        SettlementService service = new SettlementService(ds);
        List<SettlementService.NetPosition> positions = service.getNetPositions();

        assertEquals(2, positions.size());
        assertEquals("TERM-A", positions.get(0).getTerminalId());
        assertEquals(2000L, positions.get(0).getNetAmount());
        assertEquals("TERM-B", positions.get(1).getTerminalId());
        assertEquals(900L, positions.get(1).getNetAmount());
    }

    @Test
    void shouldRunSettlementFlowAndReflectBatchInNetPositions() {
        StubDataSource ds = new StubDataSource();
        ds.addTransaction(20L, "APPROVED", false, "TERM-A", 1000L);
        ds.addTransaction(21L, "AUTHORIZED", false, "TERM-B", 500L);
        ds.addTransaction(22L, "APPROVED", true, "TERM-A", 200L);
        ds.addTransaction(23L, "DECLINED", false, "TERM-C", 700L);

        SettlementService service = new SettlementService(ds);
        SettlementService.SettlementBatchSummary summary = service.runSettlement();
        List<SettlementService.NetPosition> positions = service.getNetPositions();

        assertEquals(2, summary.getTotalCount());
        assertEquals(1500L, summary.getTotalAmount());
        assertEquals(1, ds.savedBatches.size());

        assertTrue(ds.getTx(20L).settled);
        assertTrue(ds.getTx(21L).settled);
        assertTrue(ds.getTx(22L).settled);
        assertFalse(ds.getTx(23L).settled);

        assertEquals(summary.getBatchId(), ds.getTx(20L).batchId);
        assertEquals(summary.getBatchId(), ds.getTx(21L).batchId);

        assertEquals(2, positions.size());
        assertEquals("TERM-A", positions.get(0).getTerminalId());
        assertEquals(1200L, positions.get(0).getNetAmount());
        assertEquals("TERM-B", positions.get(1).getTerminalId());
        assertEquals(500L, positions.get(1).getNetAmount());
    }

    @Test
    void shouldCreateEmptyBatchWhenNoEligibleTransactions() {
        StubDataSource ds = new StubDataSource();
        ds.addTransaction(30L, "DECLINED", false, "TERM-A", 1000L);
        ds.addTransaction(31L, "REVERSED", false, "TERM-B", 2000L);
        ds.addTransaction(32L, "APPROVED", true, "TERM-C", 3000L);

        SettlementService service = new SettlementService(ds);
        SettlementService.SettlementBatchSummary summary = service.runSettlement();

        assertNotNull(summary.getBatchId());
        assertEquals(0, summary.getTotalCount());
        assertEquals(0L, summary.getTotalAmount());
        assertEquals(1, ds.savedBatches.size());
        assertEquals(0, ds.savedBatches.get(0).totalCount);
        assertEquals(0L, ds.savedBatches.get(0).totalAmount);

        assertFalse(ds.getTx(30L).settled);
        assertFalse(ds.getTx(31L).settled);
        assertTrue(ds.getTx(32L).settled);
        assertNull(ds.getTx(30L).batchId);
        assertNull(ds.getTx(31L).batchId);
    }

    private static final class TxRow {
        private final long id;
        private final String status;
        private boolean settled;
        private String terminalId;
        private long amount;
        private String batchId;

        private TxRow(long id, String status, boolean settled, String terminalId, long amount) {
            this.id = id;
            this.status = status;
            this.settled = settled;
            this.terminalId = terminalId;
            this.amount = amount;
        }
    }

    private static final class BatchRow {
        private final String batchId;
        private final int totalCount;
        private final long totalAmount;

        private BatchRow(String batchId, int totalCount, long totalAmount) {
            this.batchId = batchId;
            this.totalCount = totalCount;
            this.totalAmount = totalAmount;
        }
    }

    private static final class StubDataSource implements DataSource {
        private final Map<Long, TxRow> transactions = new HashMap<>();
        private final List<BatchRow> savedBatches = new ArrayList<>();

        private void addTransaction(long id, String status, boolean settled, String terminalId, long amount) {
            transactions.put(id, new TxRow(id, status, settled, terminalId, amount));
        }

        private TxRow getTx(long id) {
            return transactions.get(id);
        }

        @Override
        public Connection getConnection() {
            InvocationHandler connectionHandler = (proxy, method, args) -> {
                String name = method.getName();
                if ("prepareStatement".equals(name)) {
                    String sql = (String) args[0];
                    return createPreparedStatement(sql);
                }
                if ("close".equals(name)) {
                    return null;
                }
                if ("setAutoCommit".equals(name) || "commit".equals(name) || "rollback".equals(name)) {
                    return null;
                }
                if ("getAutoCommit".equals(name)) {
                    return true;
                }
                if ("unwrap".equals(name)) {
                    throw new SQLException("Unsupported unwrap");
                }
                if ("isWrapperFor".equals(name)) {
                    return false;
                }
                throw new UnsupportedOperationException("Unsupported Connection method: " + name);
            };

            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                connectionHandler
            );
        }

        private PreparedStatement createPreparedStatement(String sql) {
            String normalized = sql.toLowerCase(Locale.ROOT);
            List<Object> params = new ArrayList<>();
            List<Long> batchedIds = new ArrayList<>();
            final String[] batchIdHolder = new String[1];

            InvocationHandler psHandler = (proxy, method, args) -> {
                String name = method.getName();
                if ("setString".equals(name) || "setLong".equals(name) || "setInt".equals(name)) {
                    int index = (int) args[0];
                    while (params.size() < index) {
                        params.add(null);
                    }
                    params.set(index - 1, args[1]);
                    return null;
                }
                if ("addBatch".equals(name)) {
                    batchIdHolder[0] = (String) params.get(0);
                    batchedIds.add((Long) params.get(1));
                    return null;
                }
                if ("executeBatch".equals(name)) {
                    int[] result = new int[batchedIds.size()];
                    for (int i = 0; i < batchedIds.size(); i++) {
                        Long id = batchedIds.get(i);
                        TxRow tx = transactions.get(id);
                        if (tx != null) {
                            tx.settled = true;
                            tx.batchId = batchIdHolder[0];
                            result[i] = 1;
                        } else {
                            result[i] = 0;
                        }
                    }
                    return result;
                }
                if ("executeUpdate".equals(name)) {
                    if (normalized.contains("insert into settlement_batches")) {
                        savedBatches.add(new BatchRow((String) params.get(0), (Integer) params.get(1), (Long) params.get(2)));
                        return 1;
                    }
                    return 0;
                }
                if ("executeQuery".equals(name)) {
                    if (normalized.contains("from transactions") && normalized.contains("for update")) {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (TxRow tx : transactions.values()) {
                            boolean eligibleStatus = "AUTHORIZED".equals(tx.status) || "APPROVED".equals(tx.status);
                            if (eligibleStatus && !tx.settled) {
                                Map<String, Object> row = new HashMap<>();
                                row.put("id", tx.id);
                                row.put("amount", tx.amount);
                                rows.add(row);
                            }
                        }
                        rows.sort((a, b) -> Long.compare((Long) a.get("id"), (Long) b.get("id")));
                        return createResultSet(rows);
                    }

                    if (normalized.contains("sum(amount) as net_amount")) {
                        Map<String, Long> grouped = new HashMap<>();
                        for (TxRow tx : transactions.values()) {
                            if (tx.settled) {
                                grouped.put(tx.terminalId, grouped.getOrDefault(tx.terminalId, 0L) + tx.amount);
                            }
                        }
                        List<Map<String, Object>> rows = new ArrayList<>();
                        grouped.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> {
                                Map<String, Object> row = new HashMap<>();
                                row.put("terminal_id", entry.getKey());
                                row.put("net_amount", entry.getValue());
                                rows.add(row);
                            });
                        return createResultSet(rows);
                    }

                    return createResultSet(List.of());
                }
                if ("close".equals(name)) {
                    return null;
                }
                if ("unwrap".equals(name)) {
                    throw new SQLException("Unsupported unwrap");
                }
                if ("isWrapperFor".equals(name)) {
                    return false;
                }
                throw new UnsupportedOperationException("Unsupported PreparedStatement method: " + name);
            };

            return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                psHandler
            );
        }

        private ResultSet createResultSet(List<Map<String, Object>> rows) {
            final int[] cursor = {-1};

            InvocationHandler rsHandler = (proxy, method, args) -> {
                String name = method.getName();
                if ("next".equals(name)) {
                    cursor[0]++;
                    return cursor[0] < rows.size();
                }
                if ("getLong".equals(name)) {
                    String column = (String) args[0];
                    return (Long) rows.get(cursor[0]).get(column);
                }
                if ("getString".equals(name)) {
                    String column = (String) args[0];
                    return (String) rows.get(cursor[0]).get(column);
                }
                if ("close".equals(name)) {
                    return null;
                }
                if ("unwrap".equals(name)) {
                    throw new SQLException("Unsupported unwrap");
                }
                if ("isWrapperFor".equals(name)) {
                    return false;
                }
                throw new UnsupportedOperationException("Unsupported ResultSet method: " + name);
            };

            return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                rsHandler
            );
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
