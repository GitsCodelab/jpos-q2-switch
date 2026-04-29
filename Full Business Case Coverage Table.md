| #  | Area          | Scenario                     | Covered Now | Expected Behavior          | Priority    |
| -- | ------------- | ---------------------------- | ----------- | -------------------------- | ----------- |
| 1  | Authorization | 0100 success                 | 🟢 Yes      | RC=00                      | High        |
| 2  | Authorization | 0100 decline                 | 🟢 Yes      | RC=05                      | High        |
| 3  | Financial     | 0200 success                 | 🟢 Yes      | RC=00                      | High        |
| 4  | Security      | Invalid MAC                  | 🟢 Yes      | RC=96                      | High        |
| 5  | Security      | Tampered payload             | 🟢 Yes      | RC=96                      | High        |
| 6  | Security      | Incomplete security          | 🟢 Yes      | RC=96                      | High        |
| 7  | Timeout       | Request timeout              | 🟢 Yes      | TIMEOUT                    | High        |
| 8  | Reversal      | Auto reversal after timeout  | 🟢 Yes      | RC=00                      | High        |
| 9  | Replay        | Same request replay          | 🟢 Yes      | Same response              | High        |
| 10 | Duplicate     | Same STAN same request       | 🔴 No       | Idempotent / same response | 🔥 Critical |
| 11 | Reversal      | Unknown STAN                 | 🔴 No       | RC=25 (not found)          | 🔥 Critical |
| 12 | Reversal      | Duplicate reversal           | 🔴 No       | Reject or ignore           | High        |
| 13 | Reversal      | Partial reversal             | 🔴 No       | Adjust or reject           | Medium      |
| 14 | Validation    | Missing STAN (field 11)      | 🔴 No       | RC=30                      | 🔥 Critical |
| 15 | Validation    | Missing amount (field 4)     | 🔴 No       | RC=30                      | 🔥 Critical |
| 16 | Validation    | Invalid MTI (e.g. 9999)      | 🔴 No       | RC=30 / 12                 | High        |
| 17 | Validation    | Invalid field format         | 🔴 No       | RC=30 / 96                 | High        |
| 18 | Lifecycle     | Late response after timeout  | 🔴 No       | Ignore or reconcile        | High        |
| 19 | Routing       | MUX unavailable              | 🔴 No       | Fallback to local          | 🔥 Critical |
| 20 | Routing       | MUX success path             | 🟡 Partial  | Forward + return response  | High        |
| 21 | Terminal      | Invalid terminal (field 41)  | 🔴 No       | RC=58 / 05                 | Medium      |
| 22 | Merchant      | Invalid merchant (field 42)  | 🔴 No       | RC=03 / 05                 | Medium      |
| 23 | Integrity     | Field manipulation detection | 🟢 Yes      | RC=96                      | High        |
| 24 | Robustness    | Incomplete message           | 🟢 Yes      | Reject                     | High        |
| 25 | Logging       | ISO dump visibility          | 🟢 Yes      | Full dump                  | Medium      |
| 26 | Logging       | Raw HEX logging              | 🟡 Optional | Debug only                 | Low         |
