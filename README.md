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
