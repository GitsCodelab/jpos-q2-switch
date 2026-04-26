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
