package com.qswitch.settlement;

import com.qswitch.recon.DBConnectionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * One-shot runner that executes:
 * 1) Settlement batch creation and transaction settlement
 * 2) Net settlement computation and persistence
 */
public final class FullSettlementRunner {

    private FullSettlementRunner() {
    }

    public static void main(String[] args) {
        DataSource dataSource = DBConnectionManager.getDataSource();

        SettlementService settlementService = new SettlementService(dataSource);
        SettlementService.SettlementBatchSummary summary = settlementService.runSettlement();

        System.out.println(
            "Settlement batch=" + summary.getBatchId() +
            " count=" + summary.getTotalCount() +
            " amount=" + summary.getTotalAmount()
        );

        List<SettlementService.NetPosition> positions = settlementService.getNetPositions();
        if (positions.isEmpty()) {
            System.out.println("No terminal net positions available");
        } else {
            System.out.println("Terminal net positions:");
            for (SettlementService.NetPosition position : positions) {
                System.out.println(position.getTerminalId() + " => " + position.getNetAmount());
            }
        }

        NetSettlementService netSettlementService = new NetSettlementService(dataSource);
        Map<String, Long> bankNetPositions = netSettlementService.runFullSettlement(summary.getBatchId());

        if (bankNetPositions.isEmpty()) {
            System.out.println("No bank net positions available");
            return;
        }

        netSettlementService.printNetPositions(bankNetPositions);
    }
}
