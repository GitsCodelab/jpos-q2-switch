# ATM Switch Replacement Plan (jPOS + Custom BO)

## 🎯 Objective

Replace **SmartVista FE/BO** with:

* **Switch Core (jPOS/Q2)** for ISO8583 processing
* **Custom Back Office** for reconciliation, settlement, and reporting

---

## 🧭 Target Architecture

```
ATM/POS
   ↓
[jPOS Q2 Switch]  ← Source of Truth
   ↓
Host / Scheme / Core Banking

           ↘
        [Reconciliation Engine]
           ↓
        Reports / Settlement / Audit
```

---

## 🟢 Phase 0 — Current State (Baseline)

### Status

* ISO8583 handling ✔
* Lifecycle (0100/0200/0400) ✔
* Reversal logic ✔
* MAC + DUKPT (software) ✔
* Replay protection ✔
* Robustness ✔
* End-to-end tests ✔

### Gap

* ❌ No persistence
* ❌ No recovery
* ❌ No reconciliation
* ❌ No settlement

---

## 🟢 Phase 1 — Persistence Layer (MANDATORY)

### Goal

Make the switch **durable, restart-safe, and auditable**

### Deliverables

#### 1. Database Schema

**transactions**

* id (PK)
* stan (field 11)
* rrn (field 37)
* terminal_id (field 41)
* mti
* amount
* status (STARTED / AUTHORIZED / COMPLETED / DECLINED / FAILED / REVERSED)
* created_at
* updated_at

**transaction_events**

* id (PK)
* stan
* rrn
* mti (0100 / 0200 / 0400)
* request_payload
* response_payload
* rc
* event_type (REQUEST / RESPONSE / TIMEOUT / REVERSAL)
* created_at

**constraints**

* unique(stan, terminal_id)
* index(rrn)

---

#### 2. Java Integration (jPOS)

Files to update:

* `SwitchListener.java`
* `TransactionService.java`
* `ReversalService.java`

Add:

* `TransactionRepository`
* `EventRepository`

Flow:

```
receive → validate → persist(STARTED)
        → process → persist(status)
        → log event
```

---

#### 3. Idempotency (DB-based)

* Check existing (stan + terminal)
* Return same response if duplicate

---

#### 4. Recovery Logic

On startup:

* load pending transactions
* re-evaluate incomplete states

---

### Acceptance Criteria

* Restart does NOT lose transactions
* Duplicate STAN returns same result
* Full lifecycle stored in DB

---

## 🟡 Phase 2 — Reconciliation Engine

### Goal

Financial correctness & end-of-day validation

---

### Inputs

* `transactions`
* `transaction_events`

---

### Core Matching Logic

| Scenario              | Detection         |
| --------------------- | ----------------- |
| 0100 without 0200     | Missing financial |
| 0200 without 0100     | Orphan financial  |
| 0400 without original | Invalid reversal  |
| 0200 RC ≠ 0100 RC     | Inconsistency     |
| Duplicate STAN        | Duplicate         |

---

### Output

**reconciliation_report**

* date
* total_transactions
* matched
* unmatched
* reversed
* discrepancies

---

### Implementation

New module:

```
/recon
   ├── ReconService.java
   ├── ReconJob.java (batch/EOD)
   ├── ReconReportGenerator.java
```

---

### Acceptance Criteria

* All transactions categorized
* Discrepancies clearly reported
* EOD report generated

---

## 🟡 Phase 3 — Settlement Layer

### Goal

Replace SmartVista BO settlement functions

---

### Deliverables

* Generate scheme files (Visa/Mastercard/domestic)
* Parse incoming settlement files
* Store settlement results

---

### Suggested Structure

```
/settlement
   ├── FileGenerator.java
   ├── FileParser.java
   ├── SettlementService.java
```

---

### Acceptance Criteria

* File output matches scheme format
* Incoming files parsed successfully

---

## 🟡 Phase 4 — Parallel Run (Migration Safe Mode)

### Setup

```
ATM → SmartVista (ACTIVE)
    → jPOS (SHADOW)
```

---

### Goals

* Compare responses (RC, timing)
* Validate behavior consistency
* No production impact

---

### Acceptance Criteria

* ≥ 99.9% match with SmartVista
* No unexpected discrepancies

---

## 🟡 Phase 5 — Controlled Cutover

### Steps

* Route subset of terminals → jPOS
* Monitor logs, reconciliation, errors
* Gradually increase coverage

---

### Acceptance Criteria

* Stable processing
* No financial discrepancies

---

## 🟢 Phase 6 — Full Replacement

```
ATM → jPOS only
```

* SmartVista becomes fallback (temporary)

---

## 🔐 Future Enhancements (Post Go-Live)

* HSM integration (replace software DUKPT/MAC)
* Fraud/risk engine
* Performance tuning (TPS/load)
* Monitoring (Prometheus/Grafana)
* Alerting

---

## 📊 Project Status Summary

| Area                 | Status   |
| -------------------- | -------- |
| Switch Core          | 🟢 Done  |
| Security (MAC/DUKPT) | 🟢 Done  |
| Lifecycle/Reversal   | 🟢 Done  |
| Persistence          | 🔴 Next  |
| Reconciliation       | 🔴 Next  |
| Settlement           | 🔴 Later |
| Migration            | 🔴 Later |

---

## 🏁 Final Guiding Principle

> Phase order is NOT optional:

1. Persistence
2. Reconciliation
3. Settlement
4. Migration

Skipping order = financial risk

---

## 🚀 Next Step

➡ Start **Phase 1: Persistence Implementation**

```
```
