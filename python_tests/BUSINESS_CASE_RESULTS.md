| # | Terminal | MTI | RC | Expected | Status | Explanation |
|---|---|---|---|---|---|---|
| 1 | TERM0002 | 0100 | 05 | 05 | ✅ | Simulated decline |
| 2 | TERM0003 | 0100 | 00 | 00 | ✅ | Auth success |
| 3 | TERM0001 | 0100 | 00 | 00 | ✅ | Auth success |
| 4 | TERM0002 | 0100 | 00 | 00 | ✅ | Auth success |
| 5 | TERM0003 | 0200 | 00 | 00 | ✅ | Financial success |
| 6 | - | - | TIMEOUT | TIMEOUT | ✅ | Simulated network delay |
| 7 | TERM0001 | 0200 | 00 | 00 | ✅ | Financial success |
| 8 | TERM0002 | 0200 | 00 | 00 | ✅ | Financial success |
| 9 | TERM0001 | 0100 | 00 | 00 | ✅ | Auth success |
| 10 | - | - | TIMEOUT | TIMEOUT | ✅ | Simulated |
| 11 | TERM0002 | 0100 | 05 | 05 | ✅ | Decline |
| 12 | TERM0002 | 0100 | 00 | 00 | ✅ | Auth success |
| 13 | TERM0002 | 0200 | 00 | 00 | ✅ | Financial success |
| 14 | TERM0002 | 0100 | 00 | 00 | ✅ | Auth success |
| 15 | TERM0002 | 0200 | 00 | 00 | ✅ | Financial success |
| 16 | TERM0003 | 0100 | TIMEOUT | TIMEOUT | ✅ | Timeout scenario |
| 17 | TERM0003 | 0400 | 00 | 00 | ✅ | Auto reversal (correct) |
| 18 | TERM0001 | 0200 | TIMEOUT | TIMEOUT | ✅ | Timeout |
| 19 | TERM0001 | 0400 | 00 | 00 | ✅ | Auto reversal |
| 20 | TERM0003 | 0100 | 00 | 00 | ✅ | Auth success |
| 21 | - | - | TIMEOUT | TIMEOUT | ✅ | Simulated |
| 22 | TERM0001 | 0200 | 96 | 96 | ✅ | Invalid MAC rejected |
| 23 | TERM0001 | 0200 | 96 | 96 | ✅ | Tampered payload rejected |
| 24 | TERM0002 | 0200 | 00 | 00 | ✅ | PIN + DUKPT + MAC valid |
| 25 | TERM0003 | 0210 | 00 | 00 | ✅ | Response MAC generated |
