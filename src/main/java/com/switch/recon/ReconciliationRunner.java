package com.qswitch.recon;

import javax.sql.DataSource;
import java.util.List;

public final class ReconciliationRunner {

    private ReconciliationRunner() {
    }

    public static void main(String[] args) {
        DataSource dataSource = DBConnectionManager.getDataSource();
        ReconciliationService service = new ReconciliationService(dataSource);

        List<ReconciliationIssue> issues = service.runFullReconciliation();

        if (issues.isEmpty()) {
            System.out.println("No reconciliation issues found");
            return;
        }

        System.out.println("Reconciliation issues found:");
        issues.forEach(System.out::println);
    }
}
