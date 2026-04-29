# Phase 4: BIN Routing & Fraud Rule Test Coverage

## Overview
Phase 4 introduces intelligent BIN-based routing, fraud detection, and settlement capabilities to the ATM switch.
This document tracks all test coverage for the BIN routing engine.

---

## Database Schema (PostgreSQL)

### New Tables Created

#### 1. `bins` - BIN Routing Table
```
bin         VARCHAR(6)  PRIMARY KEY     -- 6-digit Bank Identification Number (PAN prefix)
scheme      VARCHAR(20)                 -- Routing scheme (LOCAL, VISA, MC, UNKNOWN)
issuer_id   VARCHAR(12)                 -- Issuer identifier for settlement
```

**Sample Data:**
```
123456 | LOCAL | BANK_A
654321 | VISA  | BANK_B
512345 | MC    | BANK_C
```

#### 2. `settlement_batches` - Batch Aggregation
```
id          BIGSERIAL   PRIMARY KEY
batch_id    VARCHAR(32) UNIQUE
total_count INT
total_amount BIGINT
created_at  TIMESTAMP
```

### Extended Tables

#### `transactions` - New Routing Columns
```
issuer_id        VARCHAR(12)     -- Bank identifier from BIN lookup
scheme           VARCHAR(20)     -- Routing scheme (LOCAL/VISA/MC)
retry_count      INT DEFAULT 0   -- Number of retry attempts
settled          BOOLEAN DEFAULT FALSE
settlement_date  DATE
batch_id         VARCHAR(32)
```

---

## Java Unit Tests (32 Total)

### Routing Engine Tests (6 tests)
**File**: `src/test/java/com/qswitch/routing/RoutingEngineTest.java`

| Test | Scenario | Expected | Result |
|------|----------|----------|--------|
| `shouldReturnNoDecisionWhenPanMissing` | Missing PAN field | No decision made | ✅ PASS |
| `shouldDeclineWith14WhenBinMissing` | Unknown BIN (9999991234567890) | RC=14 | ✅ PASS |
| `shouldApproveLocalBin` | LOCAL BIN (123456) small amount | RC=00 approval | ✅ PASS |
| `shouldApplyFraudDeclineForLargeLocalAmount` | LOCAL BIN with amount > 100,000 | RC=05 decline | ✅ PASS |
| `shouldRouteVisaToMux` | VISA BIN (654321) to MUX | RC=00 + isRemote=true | ✅ PASS |
| `shouldReturn91OnMuxTimeout` | MC BIN MUX timeout | RC=91 timeout | ✅ PASS |

### Fraud Rule Tests (1 test)
**File**: `src/test/java/com/qswitch/service/TransactionServiceFraudTest.java`

| Test | Scenario | Expected | Result |
|------|----------|----------|--------|
| `shouldDeclineWith05WhenAmountExceedsFraudLimit` | Amount > 100,001 | RC=05 DECLINED | ✅ PASS |

### Full Java Build
- Compilation: ✅ 25 source files
- Tests: ✅ 32 tests, 0 failures
- JAR: ✅ `lib/switch-core.jar` created

---

## Python Integration Tests (15 Total)

### New BIN/Routing/Settlement Tests (3 tests)

#### 1. `test_bin_routing_table_exists_with_sample_data`
**Purpose**: Verify BIN table exists with correct sample data

**Validations**:
- bins table exists in PostgreSQL
- Expected entries:
  - 123456 → LOCAL/BANK_A
  - 654321 → VISA/BANK_B
  - 512345 → MC/BANK_C
- scheme and issuer_id columns populated correctly

**Result**: ✅ PASS

#### 2. `test_settlement_batches_table_exists`
**Purpose**: Verify settlement_batches table is created

**Validations**:
- settlement_batches table exists
- Required columns:
  - id (BIGSERIAL)
  - batch_id (VARCHAR UNIQUE)
  - total_count (INT)
  - total_amount (BIGINT)
  - created_at (TIMESTAMP)
- Index on batch_id exists

**Result**: ✅ PASS

#### 3. `test_transactions_table_has_routing_columns`
**Purpose**: Verify routing columns added to transactions table

**Validations**:
- issuer_id (VARCHAR)
- scheme (VARCHAR)
- retry_count (INT)
- settled (BOOLEAN)
- settlement_date (DATE)
- batch_id (VARCHAR)

**Result**: ✅ PASS

### Updated Business Cases (31 Total → 35 Total)

**New Cases 28-31**:
```
28 | TERM0001 | 0200 | 00 | BIN: LOCAL (123456) approval
29 | TERM0002 | 0200 | 05 | BIN: LOCAL fraud rule (>100K)
30 | TERM0003 | 0200 | 00 | BIN: VISA (654321) MUX route
31 | TERM0001 | 0200 | 00 | BIN: MC (512345) MUX route
```

### Updated Area Status Coverage
- ✅ BIN Routing
- ✅ Fraud Rules

**Full Test Suite**: 15 tests, 0 failures, ~12.5s

---

## Database Migration

**File**: `pg/migration-phase4.sql`

Includes:
- CREATE TABLE `bins` with sample data
- CREATE TABLE `settlement_batches` with batch aggregation
- ALTER TABLE `transactions` to add routing columns
- CREATE INDEXes on routing columns for query performance

**Status**: ✅ Applied to PostgreSQL 18

---

## Routing Decision Flow

```
ISO 0200 Request
    ↓
[Field 2: PAN] → Extract first 6 digits (BIN)
    ↓
BIN Lookup in bins table
    ↓
┌─────────────────────────────────┐
│ Scheme Decision                 │
├─────────────────────────────────┤
│ LOCAL → Process locally         │
│         Check Fraud Rule:       │
│         amount > 100,000 →      │
│         → RC=05 decline         │
│         else → RC=00            │
│                                 │
│ VISA/MC → Route to MUX          │
│          Set remote=true        │
│          Handle timeout=true    │
│          → RC from MUX / RC=91  │
│                                 │
│ UNKNOWN/NULL → RC=14 decline    │
└─────────────────────────────────┘
```

---

## Test Execution Commands

### Java Unit Tests (Routing + Fraud)
```bash
mvn test -Dtest=RoutingEngineTest,TransactionServiceFraudTest
# Result: 7 tests, 0 failures
```

### Python Integration Tests (BIN/Settlement)
```bash
python -m pytest python_tests/test_full_setup_python.py::test_bin_routing_table_exists_with_sample_data -v
python -m pytest python_tests/test_full_setup_python.py::test_settlement_batches_table_exists -v
python -m pytest python_tests/test_full_setup_python.py::test_transactions_table_has_routing_columns -v
# Result: 3 tests, 0 failures
```

### All Python Tests
```bash
python -m pytest python_tests/test_full_setup_python.py -v
# Result: 15 tests, 0 failures (~12.5s)
```

### All Java Tests
```bash
mvn test
# Result: 32 tests, 0 failures
```

---

## Verification Checklist

- [x] BIN table created with sample data (LOCAL, VISA, MC)
- [x] Settlement_batches table created for batch tracking
- [x] Routing columns added to transactions table
- [x] Routing engine Java tests pass (6 tests)
- [x] Fraud rule Java tests pass (1 test)
- [x] Python BIN table validation test passes
- [x] Python settlement_batches table validation test passes
- [x] Python routing columns validation test passes
- [x] Business case scenarios 28-31 defined and passing
- [x] Area status coverage includes "BIN Routing" and "Fraud Rules"
- [x] Database migration script documented and applied
- [x] All 32 Java tests passing
- [x] All 15 Python tests passing
- [x] Code committed and pushed to main

---

## Next Steps (Phase 5+)

- **Reconciliation**: Detect routing mismatches, lost transactions
- **Settlement**: Batch marking, issuer-level net position queries, multi-party settlement
- **Auto-Reversal**: Enhanced logic for routing-specific timeouts
- **Fraud Engine**: Expand from amount threshold to velocity, pattern detection
- **Performance**: Load testing with 10K+ BINs, concurrent routing decisions

---

**Status**: ✅ Phase 4 Complete - All Tests Passing
**Last Updated**: April 29, 2026
