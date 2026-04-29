-- =========================================================
-- POPULATE SETTLEMENT DATA
-- File: populate-settlement-data.sql
-- Purpose: Add sample issuer_id, acquirer_id, and scheme data
--          to existing transactions for multi-party settlement testing
-- =========================================================

-- Populate issuer_id with sample banks (simulating BIN lookups)
UPDATE transactions SET issuer_id = 'BANK_A' WHERE id % 3 = 0 AND issuer_id IS NULL;
UPDATE transactions SET issuer_id = 'BANK_B' WHERE id % 3 = 1 AND issuer_id IS NULL;
UPDATE transactions SET issuer_id = 'BANK_C' WHERE id % 3 = 2 AND issuer_id IS NULL;

-- Populate terminal_id with sample terminals if missing
-- Skip STAN values used by tests (123456, 777777) to avoid test interference
UPDATE transactions 
SET terminal_id = 'TERM0001' 
WHERE id % 3 = 0 AND terminal_id IS NULL AND stan NOT IN ('123456', '777777');

UPDATE transactions 
SET terminal_id = 'TERM0002' 
WHERE id % 3 = 1 AND terminal_id IS NULL AND stan NOT IN ('123456', '777777');

UPDATE transactions 
SET terminal_id = 'TERM0003' 
WHERE id % 3 = 2 AND terminal_id IS NULL AND stan NOT IN ('123456', '777777');

-- Populate acquirer_id by joining with terminals table
UPDATE transactions t
SET acquirer_id = tm.acquirer_id
FROM terminals tm
WHERE t.terminal_id = tm.terminal_id AND t.acquirer_id IS NULL;

-- Set scheme based on issuer bank
UPDATE transactions SET scheme = 'LOCAL' WHERE issuer_id = 'BANK_A' AND scheme IS NULL;
UPDATE transactions SET scheme = 'VISA' WHERE issuer_id = 'BANK_B' AND scheme IS NULL;
UPDATE transactions SET scheme = 'MC' WHERE issuer_id = 'BANK_C' AND scheme IS NULL;

-- Mark authorized transactions as settled for multi-party settlement queries
UPDATE transactions 
SET settled = TRUE, settlement_date = CURRENT_DATE
WHERE status = 'AUTHORIZED' AND settled = FALSE AND issuer_id IS NOT NULL;

-- Verification
SELECT 'Settlement Data Population Complete' AS status;
SELECT issuer_id, acquirer_id, COUNT(*) as count, SUM(amount) as total_amount, settled
FROM transactions
WHERE issuer_id IS NOT NULL AND acquirer_id IS NOT NULL
GROUP BY issuer_id, acquirer_id, settled
ORDER BY issuer_id, acquirer_id, settled;
