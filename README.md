# jPOS-EE Q2 Switch

This setup is rebuilt on top of the jPOS-EE stack (`org.jpos.ee`) and keeps your requested switch structure.

## Structure

- `deploy/`: Q2 deployment descriptors
- `cfg/`: ISO8583 packager descriptor
- `lib/`: packaged JAR output (`switch-core.jar`)
- `src/main/java/com/switch`: listener, services, DAO, model, and crypto utils
- `src/test/java/com/switch`: unit tests
- `docker/`: runtime container image definition

Current active deploy files:

- `deploy/10_channel.xml`
- `deploy/20_mux.xml`
- `deploy/30_switch.xml`

## Prerequisites

- Java 17+
- Maven 3.9+

## Commands

```bash
mvn clean test
mvn clean package
```

The package phase writes `lib/switch-core.jar`.

## Docker Compose

Start the switch with Docker Compose:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose up --build
```

If startup fails with `bind: address already in use` on port `9000`, stop local Q2/Java listeners first:

```bash
cd /home/samehabib/jpos-q2-switch
pkill -f 'org.jpos.q2.Q2' || true
fuser -k 9000/tcp || true
docker compose down --remove-orphans
```

Then run:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose up --build -d
```

Start it in the background:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose up --build -d
```

Check container status:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose ps
```

Follow logs:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose logs -f switch
```

Stop everything:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose down
```

Notes:

- The Docker image builds the Maven project inside the container, so the first `docker compose up --build` can take a few minutes.
- Docker Compose is configured to pass JVM flags through `JAVA_OPTS`.
- HEX logging is currently enabled in `docker-compose.yml` with `JAVA_OPTS: -Dswitch.listener.debug=true`.
- With HEX logging enabled, the switch logs summary lines, a safe ISO dump, and raw packed ISO HEX.
- Raw HEX can include sensitive data. Turn it off outside troubleshooting.

PostgreSQL 18+ note:

- Compose now mounts PostgreSQL storage at `/var/lib/postgresql` (not `/var/lib/postgresql/data`) to match the official Postgres 18+ image behavior.
- If you previously used an older layout and see an error mentioning `/var/lib/postgresql/data (unused mount/volume)`, run this one-time reset:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose down -v
docker volume rm jpos-q2-switch_postgres-data 2>/dev/null || true
docker compose up --build -d
```

- This reset removes old PostgreSQL container data. If you need existing data, migrate it with `pg_upgrade` before removing volumes.

Switch database environment variables (in `docker-compose.yml`):

- `DB_HOST=jpos-postgresql`
- `DB_PORT=5432`
- `DB_NAME=jpos`
- `DB_USER=postgres`
- `DB_PASSWORD=postgres`
- `DB_URL=jdbc:postgresql://jpos-postgresql:5432/jpos`
- `DB_POOL_MAX_SIZE=10` (optional)
- `DB_CONNECT_RETRIES=3` (optional)
- `DB_CONNECT_RETRY_DELAY_MS=150` (optional)
- `DB_IN_MEMORY_MIRROR=false` (optional; defaults off when DB is enabled)

Java switch persistence behavior:

- DB persistence is handled by Java runtime (`SwitchListener` + DAO layer) for request and response paths.
- Java persistence uses pooled JDBC connections (HikariCP), not per-operation `DriverManager` connects.
- Persistence is enabled by default in Java.
- To explicitly disable DB writes (debug only), set `DB_PERSISTENCE_ENABLED=false`.

Initialize schema (first run or after volume reset):

```bash
cd /home/samehabib/jpos-q2-switch
docker compose exec -T jpos-postgresql psql -U postgres -f /docker-entrypoint-initdb.d/db.sql
```

If your DB was created with older constraints, run this migration once:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose exec -T jpos-postgresql psql -U postgres -d jpos \
	-c "WITH ranked AS (SELECT ctid, ROW_NUMBER() OVER (PARTITION BY stan, rrn ORDER BY id DESC) AS rn FROM transactions) DELETE FROM transactions t USING ranked r WHERE t.ctid=r.ctid AND r.rn>1;" \
	-c "ALTER TABLE transactions DROP CONSTRAINT IF EXISTS uq_stan_terminal;" \
	-c "ALTER TABLE transactions ADD CONSTRAINT uq_transactions_stan_rrn UNIQUE (stan, rrn);" \
	-c "CREATE INDEX IF NOT EXISTS idx_transactions_stan ON transactions(stan);" \
	-c "CREATE INDEX IF NOT EXISTS idx_transactions_rrn ON transactions(rrn);"
```

Add lifecycle dedupe constraints for event/meta tables (one-time migration):

```bash
cd /home/samehabib/jpos-q2-switch
docker compose exec -T jpos-postgresql psql -U postgres -d jpos \
	-c "WITH ranked AS (SELECT ctid, ROW_NUMBER() OVER (PARTITION BY stan, rrn, event_type ORDER BY id DESC) AS rn FROM transaction_events) DELETE FROM transaction_events e USING ranked r WHERE e.ctid=r.ctid AND r.rn>1;" \
	-c "ALTER TABLE transaction_events DROP CONSTRAINT IF EXISTS uq_transaction_events_stan_rrn_type;" \
	-c "ALTER TABLE transaction_events ADD CONSTRAINT uq_transaction_events_stan_rrn_type UNIQUE (stan, rrn, event_type);" \
	-c "WITH ranked AS (SELECT ctid, ROW_NUMBER() OVER (PARTITION BY stan ORDER BY id DESC) AS rn FROM transaction_meta) DELETE FROM transaction_meta m USING ranked r WHERE m.ctid=r.ctid AND r.rn>1;" \
	-c "ALTER TABLE transaction_meta DROP CONSTRAINT IF EXISTS uq_transaction_meta_stan;" \
	-c "ALTER TABLE transaction_meta ADD CONSTRAINT uq_transaction_meta_stan UNIQUE (stan);"
```

If `transactions.amount` is still `numeric(15,2)` in an older DB, convert it to minor-unit `BIGINT`:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose exec -T jpos-postgresql psql -U postgres -d jpos \
  -c "ALTER TABLE transactions ALTER COLUMN amount TYPE BIGINT USING ROUND(amount * 100)::BIGINT;"
```

After this migration, Java persists `amount` as ISO minor units directly (example: `10000` means `100.00`).

Persistence model:

- `transactions`: one row per ISO flow (`MTI`, `STAN`, `RRN`, `terminal_id`, amount, `rc`, `status`, `final_status`)
- `transaction_events`: detailed request/response payload snapshots with event type (`REQUEST`, `LOCAL_RESPONSE`, `SECURITY_DECLINE`, `MUX_RESPONSE`, etc.)
- `transaction_meta`: supporting metadata (acquirer IDs, processing code)

Lifecycle status behavior:

- Incoming request insert starts with: `status=REQUEST_RECEIVED`, `final_status=PENDING`.
- Outgoing response update always sets `rc`, `status`, and `final_status` together (`UPDATE ... rc=?, status=?, final_status=?`).
- `RC=96` (or `SECURITY_DECLINE`) maps to `status=SECURITY_DECLINE`.
- `RC=91` (or timeout event) maps to `status=TIMEOUT` and `final_status=TIMEOUT`.
- `RC=00` maps to `status=APPROVED`, other decline RCs map to `status=DECLINED`.

Idempotency and uniqueness:

- `transaction_events` is deduplicated by `UNIQUE (stan, rrn, event_type)`.
- `transaction_meta` is deduplicated by `UNIQUE (stan)` and Java uses `ON CONFLICT (stan)` upsert semantics.

Verification query examples:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose exec -T jpos-postgresql psql -U postgres -d jpos -c "SELECT id,stan,rrn,mti,rc,status,final_status,created_at FROM transactions ORDER BY id DESC LIMIT 10;"
docker compose exec -T jpos-postgresql psql -U postgres -d jpos -c "SELECT id,stan,event_type,left(request_iso,120) AS request_head,left(response_iso,120) AS response_head,created_at FROM transaction_events ORDER BY id DESC LIMIT 10;"
```

## Reconciliation Service

The project includes a reconciliation module that reads persisted data and reports operational gaps.

Location:

- `src/main/java/com/switch/recon/ReconciliationService.java`
- `src/main/java/com/switch/recon/ReconciliationIssue.java`
- `src/main/java/com/switch/recon/ReconciliationRunner.java`
- `src/main/java/com/switch/recon/AutoReversalService.java`
- `src/main/java/com/switch/recon/AutoReversalRunner.java`

What it detects:

- Missing responses: request rows that remain in `REQUEST_RECEIVED` beyond threshold.
- Reversal candidates: approved/authorized rows that exceeded reversal window and are not marked reversal.
- Lifecycle mismatches: invalid status/RC/final-status combinations.
- Event inconsistencies: missing `REQUEST` event or missing terminal event for completed lifecycles.

Runner:

- Main class: `com.qswitch.recon.ReconciliationRunner`
- The runner uses existing pooled DB connectivity and prints either:
	- `No reconciliation issues found`
	- or a list of `Issue{stan='...', rrn='...', type='...', description='...'}` lines.

Unit test coverage:

- `src/test/java/com/qswitch/recon/ReconciliationServiceTest.java`
- Covers each detector plus full aggregation and SQL parameter binding.

Run reconciliation tests only:

```bash
cd /home/samehabib/jpos-q2-switch
mvn -q -Dtest=ReconciliationServiceTest test
```

### Auto-Reversal & Recovery Engine

Scope:

- Detect reversal candidates from reconciliation (`findReversalCandidates`).
- Build and send ISO `0400` reversal messages over MUX.
- Persist reversal outcome back to `transactions` and `transaction_events`.
- Prevent duplicate reversals with both DB-state checks and in-run deduping.

Safety controls implemented:

- Idempotency: skip when transaction is already `REVERSED` or `is_reversal=true`.
- Timeout handling: no MUX response maps to `RC=91` and `status=REVERSAL_FAILED`.
- Logging: start/skip/retry/failure paths are logged to stdout/stderr.
- Retry with bounded exponential backoff:
	- default retries: `3`
	- default backoff: `250ms`, then doubled per retry.

Persistence behavior:

- Success (`RC=00`):
	- `transactions.status=REVERSED`
	- `transactions.final_status=AUTO_REVERSAL`
	- `transactions.is_reversal=true`
- Failure/timeout:
	- `transactions.status=REVERSAL_FAILED`
	- `transactions.final_status=AUTO_REVERSAL_FAILED`
	- `transactions.is_reversal=false`
- Event audit:
	- inserts a `REVERSAL` row in `transaction_events` with `request_iso`, `response_iso`, and `rc`
	- uses `ON CONFLICT (stan, rrn, event_type) DO NOTHING` for duplicate protection.

Run auto-reversal manually:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose up -d
mvn -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.qswitch.recon.AutoReversalRunner \
  -Drecon.mux.name=mux.acquirer-mux \
  -Drecon.reversal.threshold.seconds=60
```

Run auto-reversal tests only:

```bash
cd /home/samehabib/jpos-q2-switch
mvn -q -Dtest=ReconciliationServiceTest,AutoReversalServiceTest test
```

## Settlement & Clearing Engine (Phase 3)

Settlement lifecycle extension:

- `0200` approved/authorized -> eligible for settlement
- `0400` reversal path remains unchanged
- settlement run marks financial completion and batch assignment

Schema additions in `transactions`:

- `settled BOOLEAN DEFAULT FALSE`
- `settlement_date DATE`
- `batch_id VARCHAR(32)`

Additional table:

- `settlement_batches(batch_id, total_count, total_amount, created_at)`

If your database already exists, run this one-time migration:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose exec -T jpos-postgresql psql -U postgres -d jpos \
	-c "ALTER TABLE transactions ADD COLUMN IF NOT EXISTS settled BOOLEAN DEFAULT FALSE;" \
	-c "ALTER TABLE transactions ADD COLUMN IF NOT EXISTS settlement_date DATE;" \
	-c "ALTER TABLE transactions ADD COLUMN IF NOT EXISTS batch_id VARCHAR(32);" \
	-c "CREATE TABLE IF NOT EXISTS settlement_batches (id BIGSERIAL PRIMARY KEY, batch_id VARCHAR(32) UNIQUE, total_count INT, total_amount BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);" \
	-c "CREATE INDEX IF NOT EXISTS idx_transactions_settled ON transactions(settled);" \
	-c "CREATE INDEX IF NOT EXISTS idx_transactions_batch_id ON transactions(batch_id);" \
	-c "CREATE INDEX IF NOT EXISTS idx_settlement_batches_batch_id ON settlement_batches(batch_id);"
```

Core implementation:

- `src/main/java/com/switch/settlement/SettlementService.java`
- `src/main/java/com/switch/settlement/SettlementRunner.java`

Settlement behavior:

- selects unsettled rows in `AUTHORIZED`/`APPROVED`
- marks rows as settled with `settlement_date=CURRENT_DATE` and generated `batch_id`
- persists one aggregate record in `settlement_batches`

Run settlement:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose up -d
mvn -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
	-Dexec.mainClass=com.qswitch.settlement.SettlementRunner
```

Net position query:

```sql
SELECT terminal_id, SUM(amount) AS net_amount
FROM transactions
WHERE settled = TRUE
GROUP BY terminal_id;
```

Run settlement tests only:

```bash
cd /home/samehabib/jpos-q2-switch
mvn -q -Dtest=SettlementServiceTest test
```

Phase 3 verification checklist:

- Settlement unit coverage:
	- `mvn -q -Dtest=SettlementServiceTest test`
- Reconciliation + auto-reversal + settlement together:
	- `mvn -q -Dtest=ReconciliationServiceTest,AutoReversalServiceTest,SettlementServiceTest test`
- Full Java regression:
	- `mvn -q test`
- Full Python validation:
	- `source .venv/bin/activate && python -m pytest -q python_tests/`

Expected outcomes:

- settlement batch is created with aggregate amount/count
- eligible rows move to `settled=TRUE` with `settlement_date` and `batch_id`
- net position query returns grouped amounts by `terminal_id`
- reconciliation and auto-reversal suites stay green with no regressions

To disable HEX logging, edit `docker-compose.yml` and clear the `JAVA_OPTS` value:

```yaml
JAVA_OPTS:
```

To run full validation in Docker without creating root-owned artifacts in the workspace:

```bash
bash docker/run-tests-docker.sh
```

This script runs containers with your host UID/GID and executes:

- `mvn -q clean test`
- `python3 -m pytest -q python_tests`

## Python Test Layer (Validation Only)

Business logic remains in Java (jPOS + Q2). Python is used only to validate setup and business-case expectations.

```bash
/home/samehabib/jpos-q2-switch/.venv/bin/python -m pytest -q python_tests
```

This command runs Python-based validation scenarios mapped to the business-case matrix.

DB persistence verification test (runtime ISO vs PostgreSQL rows):

```bash
/home/samehabib/jpos-q2-switch/.venv/bin/python -m pytest -q python_tests/test_full_setup_python.py -k persisted
```

The persistence test sends a real ISO 0200 probe to Q2, expects a 0210/96 reply,
and verifies the latest DB rows contain matching `STAN`, `RRN`, terminal, response
code, and ISO payload content.

Python test execution also generates:

- `python_tests/BUSINESS_CASE_RESULTS.md`

## Security Controls (Java Runtime)

The switch now enforces request security in Java runtime flow (jPOS + Q2):

- Request MAC validation (field `64`)
- Tamper detection (payload mutation after MAC)
- PIN block format integrity check (field `52`)
- DUKPT-derived working key usage (field `62`)
- Security decline response (`RC=96`) on invalid/missing security data
- Response MAC generation for valid secure requests

Security logic is implemented in:

- `src/main/java/com/switch/service/SecurityService.java`
- `src/main/java/com/switch/listener/SwitchListener.java`

Security tests are covered by:

- `src/test/java/com/switch/service/SecurityServiceTest.java`
- `src/test/java/com/switch/listener/SwitchListenerTest.java`

## Replay Protection and Robustness

Additional hardening is now validated end-to-end:

- Replay protection based on transaction key (`STAN` + `RRN`) with idempotent behavior
- Duplicate request handling returns same business result without double transaction insert
- Robustness handling rejects incomplete security envelopes with `RC=96`

Area status summary (from `python_tests/BUSINESS_CASE_RESULTS.md`):

- ISO Protocol: PASS
- Lifecycle: PASS
- Reversal Logic: PASS
- Failure Handling: PASS
- Security (MAC/DUKPT): PASS
- Integrity Protection: PASS
- Replay Protection: PASS
- Robustness: PASS

## Routing Architecture

Current runtime path:

- ATM
- QServer
- SwitchListener
- QMUX (`acquirer-mux`)
- Channel (`acquirer-channel`)
- Upstream acquirer / scheme

MUX routing is configured in:

- `deploy/20_mux.xml`

Channel packager property is configured as:

- `packager-config=cfg/iso87.xml`
