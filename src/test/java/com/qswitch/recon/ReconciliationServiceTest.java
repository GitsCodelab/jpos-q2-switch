package com.qswitch.recon;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationServiceTest {

    @Test
    void shouldFindMissingResponsesAndBindThreshold() {
        StubDataSource ds = new StubDataSource();
        ds.whenSqlContains("WHERE status = 'REQUEST_RECEIVED'", rows(row("stan", "111111", "rrn", "RRN-1")));

        ReconciliationService service = new ReconciliationService(ds);
        List<ReconciliationIssue> issues = service.findMissingResponses(30);

        assertEquals(1, issues.size());
        assertEquals("111111", issues.get(0).getStan());
        assertEquals("RRN-1", issues.get(0).getRrn());
        assertEquals("MISSING_RESPONSE", issues.get(0).getType());
        assertEquals("No response received within threshold", issues.get(0).getDescription());

        CapturedQuery query = ds.firstCapturedQueryContaining("WHERE status = 'REQUEST_RECEIVED'");
        assertEquals(1, query.params.size());
        assertEquals(30, query.params.get(0));
    }

    @Test
    void shouldFindReversalCandidatesAndBindThreshold() {
        StubDataSource ds = new StubDataSource();
        ds.whenSqlContains("COALESCE(is_reversal, FALSE) = FALSE", rows(row("stan", "222222", "rrn", "RRN-2")));

        ReconciliationService service = new ReconciliationService(ds);
        List<ReconciliationIssue> issues = service.findReversalCandidates(60);

        assertEquals(1, issues.size());
        assertEquals("REVERSAL_REQUIRED", issues.get(0).getType());
        assertEquals("Approved/authorized transaction exceeded reversal window", issues.get(0).getDescription());

        CapturedQuery query = ds.firstCapturedQueryContaining("COALESCE(is_reversal, FALSE) = FALSE");
        assertEquals(1, query.params.size());
        assertEquals(60, query.params.get(0));
    }

    @Test
    void shouldFindLifecycleMismatches() {
        StubDataSource ds = new StubDataSource();
        ds.whenSqlContains("final_status = 'PENDING'", rows(row("stan", "333333", "rrn", "RRN-3")));

        ReconciliationService service = new ReconciliationService(ds);
        List<ReconciliationIssue> issues = service.findLifecycleMismatches();

        assertEquals(1, issues.size());
        assertEquals("LIFECYCLE_MISMATCH", issues.get(0).getType());
        assertEquals("Transaction lifecycle state does not match RC/final status", issues.get(0).getDescription());
    }

    @Test
    void shouldFindEventInconsistencies() {
        StubDataSource ds = new StubDataSource();
        ds.whenSqlContains("LEFT JOIN transaction_events e_req", rows(row("stan", "444444", "rrn", "RRN-4")));

        ReconciliationService service = new ReconciliationService(ds);
        List<ReconciliationIssue> issues = service.findEventInconsistencies();

        assertEquals(1, issues.size());
        assertEquals("EVENT_INCONSISTENCY", issues.get(0).getType());
        assertEquals("Missing request or terminal event for transaction lifecycle", issues.get(0).getDescription());
    }

    @Test
    void shouldAggregateAllIssueTypesInFullReconciliation() {
        StubDataSource ds = new StubDataSource();
        ds.whenSqlContains("WHERE status = 'REQUEST_RECEIVED'", rows(row("stan", "100001", "rrn", "RRN-A")));
        ds.whenSqlContains("COALESCE(is_reversal, FALSE) = FALSE", rows(row("stan", "100002", "rrn", "RRN-B")));
        ds.whenSqlContains("final_status = 'PENDING'", rows(row("stan", "100003", "rrn", "RRN-C")));
        ds.whenSqlContains("LEFT JOIN transaction_events e_req", rows(row("stan", "100004", "rrn", "RRN-D")));

        ReconciliationService service = new ReconciliationService(ds);
        List<ReconciliationIssue> issues = service.runFullReconciliation();

        assertEquals(4, issues.size());
        assertTrue(issues.stream().anyMatch(i -> "MISSING_RESPONSE".equals(i.getType())));
        assertTrue(issues.stream().anyMatch(i -> "REVERSAL_REQUIRED".equals(i.getType())));
        assertTrue(issues.stream().anyMatch(i -> "LIFECYCLE_MISMATCH".equals(i.getType())));
        assertTrue(issues.stream().anyMatch(i -> "EVENT_INCONSISTENCY".equals(i.getType())));
    }

    private static Map<String, String> row(String k1, String v1, String k2, String v2) {
        Map<String, String> row = new HashMap<>();
        row.put(k1, v1);
        row.put(k2, v2);
        return row;
    }

    @SafeVarargs
    private static List<Map<String, String>> rows(Map<String, String>... values) {
        List<Map<String, String>> list = new ArrayList<>();
        for (Map<String, String> v : values) {
            list.add(v);
        }
        return list;
    }

    private static final class CapturedQuery {
        private final String sql;
        private final List<Object> params;

        private CapturedQuery(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params;
        }
    }

    private static final class QueryResponse {
        private final String sqlContains;
        private final List<Map<String, String>> rows;

        private QueryResponse(String sqlContains, List<Map<String, String>> rows) {
            this.sqlContains = sqlContains.toLowerCase(Locale.ROOT);
            this.rows = rows;
        }
    }

    private static final class StubDataSource implements DataSource {
        private final List<QueryResponse> responses = new ArrayList<>();
        private final List<CapturedQuery> capturedQueries = new ArrayList<>();

        private void whenSqlContains(String sqlContains, List<Map<String, String>> rows) {
            responses.add(new QueryResponse(sqlContains, rows));
        }

        private CapturedQuery firstCapturedQueryContaining(String sqlContains) {
            String needle = sqlContains.toLowerCase(Locale.ROOT);
            for (CapturedQuery query : capturedQueries) {
                if (query.sql.toLowerCase(Locale.ROOT).contains(needle)) {
                    return query;
                }
            }
            throw new AssertionError("No captured query contains: " + sqlContains);
        }

        @Override
        public Connection getConnection() {
            InvocationHandler connectionHandler = (proxy, method, args) -> {
                String name = method.getName();
                if ("prepareStatement".equals(name)) {
                    String sql = (String) args[0];
                    return createPreparedStatement(sql, resolveRows(sql));
                }
                if ("close".equals(name)) {
                    return null;
                }
                if ("isClosed".equals(name)) {
                    return false;
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

        private List<Map<String, String>> resolveRows(String sql) {
            String normalized = sql.toLowerCase(Locale.ROOT);
            for (QueryResponse response : responses) {
                if (normalized.contains(response.sqlContains)) {
                    return response.rows;
                }
            }
            return List.of();
        }

        private PreparedStatement createPreparedStatement(String sql, List<Map<String, String>> resultRows) {
            List<Object> params = new ArrayList<>();

            InvocationHandler psHandler = (proxy, method, args) -> {
                String name = method.getName();
                if ("setObject".equals(name)) {
                    int index = (int) args[0];
                    while (params.size() < index) {
                        params.add(null);
                    }
                    params.set(index - 1, args[1]);
                    return null;
                }
                if ("executeQuery".equals(name)) {
                    capturedQueries.add(new CapturedQuery(sql, new ArrayList<>(params)));
                    return createResultSet(resultRows);
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

        private ResultSet createResultSet(List<Map<String, String>> resultRows) {
            final int[] cursor = {-1};

            InvocationHandler rsHandler = (proxy, method, args) -> {
                String name = method.getName();
                if ("next".equals(name)) {
                    cursor[0]++;
                    return cursor[0] < resultRows.size();
                }
                if ("getString".equals(name)) {
                    String column = (String) args[0];
                    return resultRows.get(cursor[0]).get(column);
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
            throw new UnsupportedOperationException("Not needed for tests");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // Not needed for tests.
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // Not needed for tests.
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
            throw new UnsupportedOperationException("Not needed for tests");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}