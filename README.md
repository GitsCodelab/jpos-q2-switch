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

Java switch persistence behavior:

- DB persistence is handled by Java runtime (`SwitchListener` + DAO layer) for request and response paths.
- Persistence is enabled by default in Java.
- To explicitly disable DB writes (debug only), set `DB_PERSISTENCE_ENABLED=false`.

Initialize schema (first run or after volume reset):

```bash
cd /home/samehabib/jpos-q2-switch
docker compose exec -T jpos-postgresql psql -U postgres -f /docker-entrypoint-initdb.d/db.sql
```

Persistence model:

- `transactions`: one row per ISO flow (`MTI`, `STAN`, `RRN`, `terminal_id`, amount, `rc`, `status`, `final_status`)
- `transaction_events`: detailed request/response payload snapshots with event type (`REQUEST`, `LOCAL_RESPONSE`, `SECURITY_DECLINE`, `MUX_RESPONSE`, etc.)
- `transaction_meta`: supporting metadata (acquirer IDs, processing code)

Verification query examples:

```bash
cd /home/samehabib/jpos-q2-switch
docker compose exec -T jpos-postgresql psql -U postgres -d jpos -c "SELECT id,stan,rrn,mti,rc,status,final_status,created_at FROM transactions ORDER BY id DESC LIMIT 10;"
docker compose exec -T jpos-postgresql psql -U postgres -d jpos -c "SELECT id,stan,event_type,left(request_iso,120) AS request_head,left(response_iso,120) AS response_head,created_at FROM transaction_events ORDER BY id DESC LIMIT 10;"
```

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
