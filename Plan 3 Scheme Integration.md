# Plan 3 — Scheme Integration (Visa / Mastercard / Domestic Network)

## 🎯 Objective

Integrate the jPOS switch with external payment schemes to:

* route off-us transactions
* receive issuer responses
* support certification and production traffic
* replace SmartVista FE routing gradually

---

## 🧠 Concept

jPOS becomes the **network-facing switch**:

```text id="3q3m9q"
ATM → jPOS → Scheme → Issuer Bank
```

---

## 🧱 Architecture

```text id="u7b5ek"
ATM
 ↓
jPOS Switch
 ├── ISO Handling
 ├── Security (MAC / DUKPT)
 ├── Lifecycle Engine
 ├── Persistence (DB)
 ├── Routing Logic
 │      ├── On-us → internal
 │      ├── SVFE → Plan 2 (optional)
 │      └── Scheme → Plan 3
 ↓
MUX (Scheme)
 ↓
Scheme Channel
 ↓
Payment Network (Visa / Mastercard / Local)
```

---

## 🔑 Core Components

### Using jPOS

| Component      | Role                      |
| -------------- | ------------------------- |
| Channel        | TCP connection to scheme  |
| Packager       | scheme ISO format         |
| MUX            | request/response matching |
| SwitchListener | routing logic             |

---

## 🔌 jPOS Configuration

### 1. Scheme Channel

```xml id="1h7jtf"
<channel name="scheme-channel"
         class="org.jpos.iso.channel.ASCIIChannel"
         packager="cfg/iso87.xml"
         host="SCHEME_IP"
         port="SCHEME_PORT"
         connect-timeout="30000"/>
```

---

### 2. Scheme MUX

```xml id="k2r9g0"
<mux name="scheme-mux" class="org.jpos.q2.iso.QMUX">
    <in>scheme-channel</in>
    <out>scheme-channel</out>
    <timeout>30000</timeout>
</mux>
```

---

## 💻 Java Integration

### Routing Logic

```java id="x3lp6v"
if (isOnUs(request)) {
    return processLocally(request);
}
else if (isSVFEEnabled()) {
    return svfeMux.request(request, 30000);
}
else {
    return schemeMux.request(request, 30000);
}
```

---

## 🧠 Message Flow

### 0200 Financial Example

```text id="s7r4dz"
ATM → jPOS → Scheme → Issuer
                     ↓
                 Response
                     ↓
ATM ← jPOS ← Scheme
```

---

## ⚠️ Critical Requirements Before Integration

### 🟢 MUST HAVE

* Persistence layer (transactions + events)
* Reconciliation engine (basic)
* Idempotency (DB-backed)
* Reversal correctness
* Full audit trail

---

### 🔴 DO NOT CONNECT IF

* no recovery after crash
* duplicate transactions possible
* no reconciliation reports
* unclear reversal logic

---

## 🔐 Security Requirements

Schemes typically require:

* ANSI X9.19 MAC
* DUKPT or HSM-based key management
* key exchange (ZMK / ZPK / TMK)
* message authentication

---

## 🧪 Certification Phase

Before production:

### Steps

1. Connect to scheme test environment
2. Execute certification test cases:

   * approvals
   * declines
   * reversals
   * timeouts
3. Validate:

   * field formats
   * response timing
   * error handling

---

## 📊 Logging & Monitoring

Log:

* outbound request
* inbound response
* latency
* RC distribution
* failure rates

---

## 🧠 Routing Strategy (Recommended)

```text id="6s3v8q"
On-us → internal
Off-us → scheme
Fallback → SVFE (temporary)
```

---

## 🟡 Migration Strategy

### Phase 1 — Shadow Mode

```text id="r1kqcf"
ATM → SVFE (active)
    → jPOS (monitor only)
```

---

### Phase 2 — Partial Routing

```text id="8m9c1w"
Some terminals → jPOS → scheme
Others → SVFE
```

---

### Phase 3 — Full Cutover

```text id="v2w3kp"
ATM → jPOS → scheme
```

---

## 🚀 Benefits

* full control over routing
* reduced dependency on SmartVista
* direct scheme integration
* scalable architecture

---

## 🏁 Final Insight

> Scheme integration is not a feature
> It is a **certification milestone**

---

## 📍 Dependencies

Plan 3 depends on:

* Plan 1 → Persistence ✔
* Plan 2 → SVFE routing (optional but recommended) ✔

---

## 🚀 Next Phase After Plan 3

* HSM integration
* performance tuning
* fraud / risk engine
* production monitoring

---
