-- =========================================================
-- PHASE 4: BIN ROUTING & SETTLEMENT MIGRATION
-- File: migration-phase4.sql
-- Target: PostgreSQL
-- Purpose: Add bins and settlement_batches tables for routing engine
-- =========================================================

-- =========================
-- 1. CREATE BINS TABLE
-- =========================
CREATE TABLE IF NOT EXISTS bins (
    bin VARCHAR(6) PRIMARY KEY,
    scheme VARCHAR(20),
    issuer_id VARCHAR(12)
);

CREATE INDEX IF NOT EXISTS idx_bins_scheme ON bins(scheme);

-- Insert sample BIN data for LOCAL, VISA, and MC schemes
INSERT INTO bins (bin, scheme, issuer_id) VALUES
    ('123456', 'LOCAL', 'BANK_A'),
    ('654321', 'VISA', 'BANK_B'),
    ('512345', 'MC', 'BANK_C')
ON CONFLICT (bin) DO NOTHING;

-- =========================
-- 2. CREATE SETTLEMENT BATCHES TABLE
-- =========================
CREATE TABLE IF NOT EXISTS settlement_batches (
    id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(32) UNIQUE,
    total_count INT,
    total_amount BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_settlement_batches_batch_id ON settlement_batches(batch_id);

-- =========================
-- 3. VERIFY MIGRATION
-- =========================
-- Run this to confirm all tables exist:
-- SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;

-- Expected tables: bins, settlement_batches, transaction_events, transaction_meta, transactions
