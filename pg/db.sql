-- =========================================================
-- SWITCH DATABASE SCHEMA
-- File: switch-db.sql
-- Target: PostgreSQL
-- =========================================================

-- =========================
-- 0. CREATE DATABASE
-- =========================
CREATE DATABASE jpos;

-- =========================
-- 1. TRANSACTIONS TABLE
-- =========================
CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,

    stan VARCHAR(12) NOT NULL,
    rrn VARCHAR(12),
    terminal_id VARCHAR(16),

    mti VARCHAR(4),
    original_mti VARCHAR(4),

    -- 🔥 MINOR UNITS (IMPORTANT)
    -- Example: 10000 = 100.00
    amount BIGINT,
    currency VARCHAR(3),

    rc VARCHAR(2),

    status VARCHAR(20),
    final_status VARCHAR(20),

    is_reversal BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_transactions_stan_rrn UNIQUE (stan, rrn)
);

CREATE INDEX idx_transactions_stan ON transactions(stan);
CREATE INDEX idx_transactions_rrn ON transactions(rrn);
CREATE INDEX idx_transactions_status ON transactions(status);

-- =========================
-- 2. TRANSACTION EVENTS
-- =========================
CREATE TABLE transaction_events (
    id BIGSERIAL PRIMARY KEY,

    stan VARCHAR(12),
    rrn VARCHAR(12),

    mti VARCHAR(4),

    event_type VARCHAR(20), -- REQUEST / RESPONSE / TIMEOUT / REVERSAL

    request_iso TEXT,
    response_iso TEXT,

    rc VARCHAR(2),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_transaction_events_stan_rrn_type UNIQUE (stan, rrn, event_type)
);

CREATE INDEX idx_events_stan ON transaction_events(stan);
CREATE INDEX idx_events_rrn ON transaction_events(rrn);

-- =========================
-- 3. TRANSACTION META
-- =========================
CREATE TABLE transaction_meta (
    id BIGSERIAL PRIMARY KEY,

    stan VARCHAR(12),

    acquirer_id VARCHAR(12),
    issuer_id VARCHAR(12),

    processing_code VARCHAR(6),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_meta_stan ON transaction_meta(stan);

-- =========================
-- END OF FILE
-- =========================