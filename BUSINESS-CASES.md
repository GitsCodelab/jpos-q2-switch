# 🧪 ATM Switch Test Coverage Matrix (Q2 / jPOS)

## 🧩 1. ISO8583 Protocol

| Area             | What to Test                  | Example          | Expected Result             | Priority | Status |
| ---------------- | ----------------------------- | ---------------- | --------------------------- | -------- | ------ |
| Message Parsing  | Correct unpacking             | 0200 message     | All fields parsed correctly | 🔴 High  | PASS   |
| Bitmap Handling  | Correct bitmap interpretation | Secondary bitmap | Fields recognized properly  | 🔴 High  | PASS   |
| Field Encoding   | BCD / ASCII correctness       | Field 4, 11      | No corruption               | 🔴 High  | PASS   |
| Field Length     | Variable/fixed fields         | LLVAR, LLLVAR    | No truncation               | 🔴 High  | PASS   |
| Response Packing | 0210/0110 format              | Response message | ATM accepts response        | 🔴 High  | PASS   |

---

## 🔐 2. Security

| Area             | What to Test        | Example        | Expected Result      | Priority | Status |
| ---------------- | ------------------- | -------------- | -------------------- | -------- | ------ |
| MAC Validation   | HMAC-SHA256         | Field 64       | Reject invalid MAC   | 🔴 High  | PASS   |
| MAC Generation   | Response MAC        | 0210 response  | Correct MAC returned | 🔴 High  | PASS   |
| DUKPT Derivation | Key per transaction | Field 62 (KSN) | Correct key derived  | 🔴 High  | PASS   |
| PIN Block        | ISO-0 format        | Field 52       | Valid PIN processing | 🔴 High  | PASS   |
| Invalid Security | Tampered message    | Wrong MAC      | Decline / reject     | 🔴 High  | PASS   |

---

## 🔁 3. Transaction Lifecycle

| Area             | What to Test          | Example             | Expected Result       | Priority | Status |
| ---------------- | --------------------- | ------------------- | --------------------- | -------- | ------ |
| 0100 Flow        | Authorization request | 0100 → 0110         | RC returned correctly | 🔴 High  | PASS   |
| 0200 Flow        | Financial request     | 0200 → 0210         | Transaction processed | 🔴 High  | PASS   |
| 0400 Flow        | Reversal              | 0400 → 0410         | Reversal handled      | 🔴 High  | PASS   |
| State Transition | Status changes        | STARTED → COMPLETED | Correct lifecycle     | 🔴 High  | PASS   |

---

## ⚠️ 4. Failure & Edge Cases

| Area              | What to Test             | Example            | Expected Result        | Priority | Status |
| ----------------- | ------------------------ | ------------------ | ---------------------- | -------- | ------ |
| Timeout           | No response from backend | 0200 timeout       | Trigger reversal       | 🔴 High  | PASS   |
| Decline           | RC=05 / 51               | Insufficient funds | No reversal            | 🔴 High  | PASS   |
| System Error      | RC=96                    | Internal error     | Mark FAILED            | 🔴 High  | PASS   |
| Reversal Timeout  | 0400 timeout             | No response        | REVERSAL_TIMEOUT       | 🔴 High  | PASS   |
| Duplicate Message | Same STAN                | Replay             | Same response returned | 🔴 High  | PASS   |

---

## 🔁 5. Idempotency

| Area             | What to Test       | Example        | Expected Result      | Priority | Status |
| ---------------- | ------------------ | -------------- | -------------------- | -------- | ------ |
| Duplicate STAN   | Same request twice | Same STAN      | No double processing | 🔴 High  | PASS   |
| Duplicate RRN    | Same RRN reuse     | Replay         | Same result returned | 🔴 High  | PASS   |
| Restart Scenario | After crash        | Replay message | Still idempotent     | 🔴 High  | PASS   |

---

## 🧾 6. Persistence (DB)

| Area               | What to Test     | Example          | Expected Result | Priority |
| ------------------ | ---------------- | ---------------- | --------------- | -------- |
| Insert Transaction | New STAN         | 0200             | Row created     | 🔴 High  |
| Update Status      | Completion       | COMPLETED        | Status updated  | 🔴 High  |
| Event Logging      | Lifecycle events | 0100/0200        | Events stored   | 🔴 High  |
| Constraints        | Unique STAN/RRN  | Duplicate insert | Rejected        | 🔴 High  |
| Recovery           | Restart system   | Existing data    | State preserved | 🔴 High  |

---

## 🧠 7. Reversal Logic

| Area                 | What to Test   | Example      | Expected Result   | Priority |
| -------------------- | -------------- | ------------ | ----------------- | -------- |
| Timeout Reversal     | 0200 timeout   | Trigger 0400 | Reversal executed | 🔴 High  |
| Decline No Reversal  | RC=05          | Decline      | No 0400 sent      | 🔴 High  |
| Invalid Reversal     | Unknown STAN   | 0400         | Reject reversal   | 🔴 High  |
| Reversal Idempotency | Duplicate 0400 | Same STAN    | No duplication    | 🔴 High  |

---

## 🧵 8. Concurrency

| Area            | What to Test     | Example             | Expected Result    | Priority |
| --------------- | ---------------- | ------------------- | ------------------ | -------- |
| Multi-Terminal  | Multiple threads | 10 terminals        | No data corruption | 🔴 High  |
| Race Conditions | Same STAN        | Concurrent requests | Safe handling      | 🔴 High  |
| DB Locking      | Parallel updates | Same record         | No inconsistency   | 🔴 High  |

---

## ⏱️ 9. Performance

| Area                  | What to Test    | Example | Expected Result | Priority  |
| --------------------- | --------------- | ------- | --------------- | --------- |
| Response Time         | 0200 processing | <200 ms | Within SLA      | 🟡 Medium |
| Throughput            | TPS load        | 50 TPS  | Stable system   | 🟡 Medium |
| Latency (Python call) | Fraud service   | <100 ms | No delay        | 🟡 Medium |

---

## 🔌 10. Integration (Q2 + Services)

| Area             | What to Test     | Example     | Expected Result   | Priority |
| ---------------- | ---------------- | ----------- | ----------------- | -------- |
| Q2 Listener      | Receive ISO      | 0200        | Message received  | 🔴 High  |
| MUX Routing      | Correct dispatch | MTI-based   | Routed properly   | 🔴 High  |
| Service Call     | Python fraud     | API call    | Response received | 🔴 High  |
| Timeout Handling | Python down      | No response | Fallback applied  | 🔴 High  |

---

## 📊 11. Logging & Audit

| Area             | What to Test    | Example        | Expected Result | Priority |
| ---------------- | --------------- | -------------- | --------------- | -------- |
| Log Completeness | STAN/RRN logged | Each tx        | Traceable       | 🔴 High  |
| Error Logs       | Failures        | Exception      | Logged clearly  | 🔴 High  |
| Audit Trail      | Full lifecycle  | 0100→0200→0400 | Reconstructable | 🔴 High  |

---

# 🏁 Final Coverage Summary

| Category    | Coverage |
| ----------- | -------- |
| Protocol    | ✅        |
| Security    | ✅        |
| Lifecycle   | ✅        |
| Failures    | ✅        |
| Idempotency | ✅        |
| Persistence | ✅        |
| Reversal    | ✅        |
| Concurrency | ✅        |
| Performance | 🟡       |
| Integration | ✅        |
| Audit       | ✅        |

---

# 🔥 Execution Advice

* Start with: **Protocol + Lifecycle**
* Then: **Failure + Reversal**
* Then: **Persistence + Idempotency**
* Finally: **Performance + Integration**

👉 This order prevents false confidence
