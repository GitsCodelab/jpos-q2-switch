package com.qswitch.recon;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ReconciliationService {

    private final DataSource dataSource;

    public ReconciliationService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<ReconciliationIssue> findMissingResponses(int secondsThreshold) {
        String sql = """
            SELECT stan, rrn
            FROM transactions
            WHERE status = 'REQUEST_RECEIVED'
              AND created_at < NOW() - (? * INTERVAL '1 second')
        """;

        return queryIssues(sql, "MISSING_RESPONSE", "No response received within threshold", secondsThreshold);
    }

    public List<ReconciliationIssue> findReversalCandidates(int secondsThreshold) {
        String sql = """
            SELECT stan, rrn
            FROM transactions
            WHERE status IN ('AUTHORIZED', 'APPROVED')
              AND COALESCE(is_reversal, FALSE) = FALSE
              AND created_at < NOW() - (? * INTERVAL '1 second')
        """;

        return queryIssues(sql, "REVERSAL_REQUIRED", "Approved/authorized transaction exceeded reversal window", secondsThreshold);
    }

    public List<ReconciliationIssue> findLifecycleMismatches() {
        String sql = """
            SELECT stan, rrn
            FROM transactions
            WHERE (status = 'REQUEST_RECEIVED' AND rc IS NOT NULL)
               OR (status IN ('AUTHORIZED', 'APPROVED', 'DECLINED', 'SECURITY_DECLINE', 'TIMEOUT') AND rc IS NULL)
               OR (final_status = 'PENDING' AND status <> 'REQUEST_RECEIVED')
               OR (status IN ('AUTHORIZED', 'APPROVED') AND rc IS NOT NULL AND rc <> '00')
        """;

        return queryIssues(sql, "LIFECYCLE_MISMATCH", "Transaction lifecycle state does not match RC/final status");
    }

    public List<ReconciliationIssue> findRcMismatch() {
        String sql = """
            SELECT stan, rrn
            FROM transactions
            WHERE status IN ('AUTHORIZED', 'APPROVED')
              AND rc IS NOT NULL
              AND rc <> '00'
        """;

        return queryIssues(sql, "RC_MISMATCH", "Authorized/approved transaction has non-00 response code");
    }

    public List<ReconciliationIssue> findEventInconsistencies() {
        String sql = """
            SELECT t.stan, t.rrn
            FROM transactions t
            LEFT JOIN transaction_events e_req
              ON e_req.stan = t.stan
             AND e_req.rrn = t.rrn
             AND e_req.event_type = 'REQUEST'
            LEFT JOIN transaction_events e_terminal
              ON e_terminal.stan = t.stan
             AND e_terminal.rrn = t.rrn
             AND e_terminal.event_type IN (
                'LOCAL_RESPONSE',
                'MUX_RESPONSE',
                'SECURITY_DECLINE',
                'TIMEOUT',
                'EXCEPTION_RESPONSE',
                'REVERSAL'
             )
            WHERE e_req.id IS NULL
               OR (t.status <> 'REQUEST_RECEIVED' AND e_terminal.id IS NULL)
        """;

        return queryIssues(sql, "EVENT_INCONSISTENCY", "Missing request or terminal event for transaction lifecycle");
    }

    public List<ReconciliationIssue> runFullReconciliation() {
        List<ReconciliationIssue> all = new ArrayList<>();

        all.addAll(findMissingResponses(30));
        all.addAll(findReversalCandidates(60));
        all.addAll(findLifecycleMismatches());
        all.addAll(findEventInconsistencies());

        return all;
    }

    private List<ReconciliationIssue> queryIssues(String sql, String type, String description, Object... params) {
        List<ReconciliationIssue> list = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new ReconciliationIssue(
                        rs.getString("stan"),
                        rs.getString("rrn"),
                        type,
                        description
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Reconciliation query failed", e);
        }

        return list;
    }
}
