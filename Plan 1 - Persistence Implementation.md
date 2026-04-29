# Plan 1 — Persistence Implementation (jPOS + PostgreSQL)

## 🎯 Objective

Add a persistence layer to the jPOS switch to:

* store transactions and lifecycle events
* enable recovery after crash
* enforce idempotency (duplicate protection)
* support reconciliation (future Plan 2/3)

---

## 🧠 Architecture

```text id="q7f8h2"
ATM
 ↓
jPOS Switch
 ├── ISO Handling
 ├── Security
 ├── Lifecycle
 ├── Routing
 └── Persistence Layer  ← (NEW)
        ↓
   PostgreSQL DB
```

---

## 🧱 Database Tables

### 1. transactions

* business state of transaction

### 2. transaction_events

* full lifecycle history (0100 / 0200 / 0400)

---

## 🔁 Data Flow

### On Request (0100 / 0200)

```text id="t1flow"
receive → validate → INSERT transaction (STARTED)
        → INSERT event (REQUEST)
```

---

### On Response

```text id="t2flow"
process → UPDATE transaction (status + RC)
        → INSERT event (RESPONSE)
```

---

### On Reversal

```text id="t3flow"
trigger → UPDATE transaction (is_reversal = true)
        → INSERT event (REVERSAL)
```

---

## 🔑 Idempotency (Critical)

* unique key: (stan + terminal_id)
* if duplicate → return stored response

---

## 🧪 Acceptance Criteria

* restart does not lose transactions
* duplicate STAN returns same result
* full lifecycle stored
* events traceable

---

## 📍 Files to Implement

```text id="filestruct"
/db
  ├── DbManager.java
  ├── TransactionRepository.java
  ├── EventRepository.java

/service
  ├── TransactionService.java

/listener
  ├── SwitchListener.java (update)
```

---

## 🚀 Implementation Steps

1. Create DB connection manager
2. Implement repositories
3. Inject into SwitchListener
4. Add persistence calls (request/response/reversal)
5. Test restart + duplicate handling

---

## 🏁 Final Insight

> Without persistence → system is correct
> With persistence → system is **financially reliable**

---
