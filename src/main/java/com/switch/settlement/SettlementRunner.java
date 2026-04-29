package com.qswitch.settlement;

import com.qswitch.recon.DBConnectionManager;

import javax.sql.DataSource;
import java.util.List;

public final class SettlementRunner {

    private SettlementRunner() {
    }

    public static void main(String[] args) {
        DataSource dataSource = DBConnectionManager.getDataSource();
        SettlementService service = new SettlementService(dataSource);

        SettlementService.SettlementBatchSummary summary = service.runSettlement();
        List<SettlementService.NetPosition> positions = service.getNetPositions();

        System.out.println(
            "Settlement batch=" + summary.getBatchId() +
            " count=" + summary.getTotalCount() +
            " amount=" + summary.getTotalAmount()
        );

        if (positions.isEmpty()) {
            System.out.println("No net positions available");
            return;
        }

        System.out.println("Net positions:");
        for (SettlementService.NetPosition position : positions) {
            System.out.println(position.getTerminalId() + " => " + position.getNetAmount());
        }
    }
}
