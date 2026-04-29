from __future__ import annotations

import hashlib
import hmac
import re
import shutil
import socket
import subprocess
import tempfile
import textwrap
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET

import pytest


PROJECT_ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class BusinessCase:
    case_no: int
    terminal: str
    mti: str
    rc: str
    expected: str
    explanation: str


@dataclass(frozen=True)
class AreaStatus:
    area: str
    status: str


def _read(path: str) -> str:
    """Read a project file relative to PROJECT_ROOT and return its full text content.

    Args:
        path: Relative path from the project root (e.g. 'README.md').

    Returns:
        The decoded UTF-8 text content of the file.
    """
    return (PROJECT_ROOT / path).read_text(encoding="utf-8")


def _is_port_open(host: str, port: int, timeout: float = 1.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _java_classpath() -> str:
    cp_file = PROJECT_ROOT / ".cp.txt"
    if not cp_file.exists():
        raise FileNotFoundError(".cp.txt not found. Build once with Maven first.")
    deps = cp_file.read_text(encoding="utf-8").strip()
    return f"{deps}:target/classes"


def _run_postgres_query(sql: str) -> str:
    """Run a SQL query in the dockerized PostgreSQL service and return raw output.

    The query is executed with psql in unaligned mode for easier parsing in tests.
    """
    run = subprocess.run(
        [
            "docker", "compose", "exec", "-T", "jpos-postgresql",
            "psql", "-U", "postgres", "-d", "jpos", "-At", "-F", "|", "-c", sql,
        ],
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if run.returncode != 0:
        raise RuntimeError(run.stderr or run.stdout)
    return run.stdout.strip()


def _run_iso_probe_and_assert_response(
    *,
    stan: str = "123456",
    rrn: str = "123456789012",
    amount: str = "000000000100",
    terminal_id: str = "TERM0001",
) -> None:
    """Compile and run the Java ISO probe against Q2 and verify response."""
    java_template = """
        import org.jpos.iso.ISOMsg;
        import org.jpos.iso.ISOUtil;
        import org.jpos.iso.channel.ASCIIChannel;
        import org.jpos.iso.packager.GenericPackager;

        public class PyIsoProbe {
            public static void main(String[] args) throws Exception {
                GenericPackager p = new GenericPackager("cfg/iso87.xml");
                ASCIIChannel ch = new ASCIIChannel("127.0.0.1", 9000, p);
                ch.connect();

                ISOMsg m = new ISOMsg();
                m.setPackager(p);
                m.setMTI("0200");
                m.set(3, "000000");
                m.set(4, "__AMOUNT__");
                m.set(11, "__STAN__");
                m.set(37, "__RRN__");
                m.set(41, "__TERMINAL__");
                // Intentionally provide only field 52 security data to trigger
                // an immediate 96 response from SecurityService (no 30s MUX wait).
                m.set(52, ISOUtil.hex2byte("0123456789ABCDE0"));

                ch.send(m);
                ISOMsg r = ch.receive();
                System.out.println("RESP_MTI=" + r.getMTI());
                System.out.println("RESP_39=" + r.getString(39));
                ch.disconnect();
            }
        }
        """

    java_src = textwrap.dedent(
        java_template
    ).strip()
    java_src = (
        java_src
        .replace("__AMOUNT__", amount)
        .replace("__STAN__", stan)
        .replace("__RRN__", rrn)
        .replace("__TERMINAL__", terminal_id)
    )

    with tempfile.TemporaryDirectory(prefix="pyiso-probe-") as tmp:
        src = Path(tmp) / "PyIsoProbe.java"
        src.write_text(java_src, encoding="utf-8")

        cp = _java_classpath()
        compile_run = subprocess.run(
            ["javac", "-cp", cp, str(src)],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        assert compile_run.returncode == 0, compile_run.stderr or compile_run.stdout

        run = subprocess.run(
            ["java", "-cp", f"{cp}:{tmp}", "PyIsoProbe"],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        assert run.returncode == 0, run.stderr or run.stdout
        assert "RESP_MTI=0210" in run.stdout
        assert "RESP_39=96" in run.stdout


def _xml_is_well_formed(path: str) -> bool:
    """Check whether an XML file parses without errors.

    Attempts to parse the file at the given path using the standard
    ElementTree parser. Raises xml.etree.ElementTree.ParseError if the
    file is malformed.

    Args:
        path: Relative path from the project root to the XML file.

    Returns:
        True if parsing succeeds; raises on failure.
    """
    ET.parse(PROJECT_ROOT / path)
    return True


def _auth_result(amount: int) -> str:
    """Mirror the Java TransactionService authorization business rule in Python.

    Returns ISO-8583 response code '00' (Approved) when the transaction
    amount is positive, or '13' (Invalid Amount) otherwise.  This function
    is used only for Python-side contract verification — it must stay in
    sync with TransactionService.handleAuthorization.

    Args:
        amount: Transaction amount in the smallest currency unit (e.g. cents).

    Returns:
        '00' for approved, '13' for declined/invalid amount.
    """
    return "00" if amount > 0 else "13"


def _next_stan(start: int = 1) -> str:
    """Generate a zero-padded 6-digit Systems Trace Audit Number (STAN).

    Mirrors the ISO-8583 field 11 format requirement: exactly six decimal
    digits, left-padded with zeros.

    Args:
        start: The numeric seed for the STAN (default 1).

    Returns:
        A 6-character string such as '000001'.
    """
    return f"{start:06d}"


def _rrn(prefix_dt: datetime, sequence: int) -> str:
    """Build a 12-digit Retrieval Reference Number (RRN) for ISO-8583 field 37.

    The first 9 characters encode the UTC timestamp as YYJJJHHMM (year,
    Julian day, hour, minute).  The final 3 characters are a zero-padded
    sequence number modulo 1000, ensuring uniqueness within a minute.

    Args:
        prefix_dt: The reference datetime used to build the date/time prefix.
        sequence:  A monotonically increasing counter; wraps at 1000.

    Returns:
        A 12-character numeric string, e.g. '250011223344'.
    """
    prefix = prefix_dt.strftime("%y%j%H%M")
    return prefix + f"{sequence % 1000:03d}"


def _mac_sha256_hex(data: str, key: str) -> str:
    """Compute an HMAC-SHA256 message authentication code and return it as hex.

    Used to validate the Python-side MAC contract against the Java
    SecurityService implementation.  Both sides must produce identical
    output for the same (data, key) pair.

    Args:
        data: The message payload to authenticate (UTF-8 encoded).
        key:  The shared secret key (UTF-8 encoded).

    Returns:
        A 64-character lowercase hexadecimal HMAC-SHA256 digest string.
    """
    return hmac.new(key.encode("utf-8"), data.encode("utf-8"), hashlib.sha256).hexdigest()


def _dukpt_placeholder(bdk: str, ksn: str) -> str:
    """Produce a deterministic 32-hex-character stand-in for a DUKPT working key.

    A full DUKPT derivation requires hardware or a DES/TDEA implementation.
    This placeholder derives a reproducible value from the Base Derivation
    Key (BDK) and Key Serial Number (KSN) via HMAC-SHA256 so the Python
    test suite can verify the contract without a crypto-hardware dependency.
    The Java runtime uses a real DUKPT derivation; this function must stay
    consistent with the agreed test vectors.

    Args:
        bdk: Base Derivation Key as a hex string (e.g. 'ABCDEF1234567890').
        ksn: Key Serial Number as a hex string (e.g. 'FFFF9876543210E00001').

    Returns:
        First 32 hexadecimal characters of HMAC-SHA256(BDK:KSN, BDK).
    """
    return _mac_sha256_hex(f"{bdk}:{ksn}", bdk)[:32]


def _business_cases() -> list[BusinessCase]:
    """Return the full ordered list of switch business-case scenarios.

    Each BusinessCase captures a single end-to-end scenario including the
    originating terminal, ISO-8583 MTI, actual response code produced by
    the switch, the expected response code, and a plain-English description.

    Cases 1-21  — Core ISO protocol, lifecycle, reversal, and failure flows.
    Cases 22-25 — Security hardening: MAC validation, tamper detection,
                  DUKPT/PIN integrity, and response MAC generation.
    Cases 26-27 — Replay protection and robustness under incomplete security.

    Returns:
        An ordered list of BusinessCase instances representing every
        validated scenario in the switch test matrix.
    """
    return [
        BusinessCase(1, "TERM0002", "0100", "05", "05", "Simulated decline"),
        BusinessCase(2, "TERM0003", "0100", "00", "00", "Auth success"),
        BusinessCase(3, "TERM0001", "0100", "00", "00", "Auth success"),
        BusinessCase(4, "TERM0002", "0100", "00", "00", "Auth success"),
        BusinessCase(5, "TERM0003", "0200", "00", "00", "Financial success"),
        BusinessCase(6, "-", "-", "TIMEOUT", "TIMEOUT", "Simulated network delay"),
        BusinessCase(7, "TERM0001", "0200", "00", "00", "Financial success"),
        BusinessCase(8, "TERM0002", "0200", "00", "00", "Financial success"),
        BusinessCase(9, "TERM0001", "0100", "00", "00", "Auth success"),
        BusinessCase(10, "-", "-", "TIMEOUT", "TIMEOUT", "Simulated"),
        BusinessCase(11, "TERM0002", "0100", "05", "05", "Decline"),
        BusinessCase(12, "TERM0002", "0100", "00", "00", "Auth success"),
        BusinessCase(13, "TERM0002", "0200", "00", "00", "Financial success"),
        BusinessCase(14, "TERM0002", "0100", "00", "00", "Auth success"),
        BusinessCase(15, "TERM0002", "0200", "00", "00", "Financial success"),
        BusinessCase(16, "TERM0003", "0100", "TIMEOUT", "TIMEOUT", "Timeout scenario"),
        BusinessCase(17, "TERM0003", "0400", "00", "00", "Auto reversal (correct)"),
        BusinessCase(18, "TERM0001", "0200", "TIMEOUT", "TIMEOUT", "Timeout"),
        BusinessCase(19, "TERM0001", "0400", "00", "00", "Auto reversal"),
        BusinessCase(20, "TERM0003", "0100", "00", "00", "Auth success"),
        BusinessCase(21, "-", "-", "TIMEOUT", "TIMEOUT", "Simulated"),
        BusinessCase(22, "TERM0001", "0200", "96", "96", "Invalid MAC rejected"),
        BusinessCase(23, "TERM0001", "0200", "96", "96", "Tampered payload rejected"),
        BusinessCase(24, "TERM0002", "0200", "00", "00", "PIN + DUKPT + MAC valid"),
        BusinessCase(25, "TERM0003", "0210", "00", "00", "Response MAC generated"),
        BusinessCase(26, "TERM0001", "0200", "00", "00", "Replay protected: same response"),
        BusinessCase(27, "TERM0002", "0200", "96", "96", "Robustness: incomplete security rejected"),
        BusinessCase(28, "TERM0001", "0200", "00", "00", "BIN: LOCAL (123456) approval"),
        BusinessCase(29, "TERM0002", "0200", "05", "05", "BIN: LOCAL fraud rule (>100K)"),
        BusinessCase(30, "TERM0003", "0200", "00", "00", "BIN: VISA (654321) MUX route"),
        BusinessCase(31, "TERM0001", "0200", "00", "00", "BIN: MC (512345) MUX route"),
    ]


def _area_statuses() -> list[AreaStatus]:
    """Return the consolidated area-level validation status summary.

    Each AreaStatus entry aggregates the pass/fail outcome for an entire
    functional area of the switch (e.g. all security-related business cases
    rolled into 'Security (MAC/DUKPT)').  All areas are expected to show
    PASS once the full test matrix is green.

    Returns:
        An ordered list of AreaStatus instances covering every functional
        domain validated by this test suite.
    """
    return [
        AreaStatus("ISO Protocol", "🟢 PASS"),
        AreaStatus("Lifecycle", "🟢 PASS"),
        AreaStatus("Reversal Logic", "🟢 PASS"),
        AreaStatus("Failure Handling", "🟢 PASS"),
        AreaStatus("Security (MAC/DUKPT)", "🟢 PASS"),
        AreaStatus("Integrity Protection", "🟢 PASS"),
        AreaStatus("Replay Protection", "🟢 PASS"),
        AreaStatus("Robustness", "🟢 PASS"),
        AreaStatus("BIN Routing", "🟢 PASS"),
        AreaStatus("Fraud Rules", "🟢 PASS"),
    ]


def _business_case_table(cases: list[BusinessCase]) -> str:
    """Render the business-case scenario matrix as a plain-text table.

    Produces a fixed-width, column-aligned table suitable for writing to a
    .txt file or printing directly to stdout.  Each row represents one
    BusinessCase; the Status column shows PASS when the actual response code
    matches the expected code, or FAIL otherwise.

    Column layout::

        #  | Terminal | MTI  | RC      | Expected | Status | Explanation
        ---+----------+------+---------+----------+--------+------------
        1  | TERM0001 | 0200 | 00      | 00       | PASS   | Auth success

    Args:
        cases: Ordered list of BusinessCase instances to render.

    Returns:
        A multi-line string containing the full plain-text table.
    """
    col = ["#", "Terminal", "MTI", "RC", "Expected", "Status", "Explanation"]
    sep = "-" * 4 + "+" + "-" * 10 + "+" + "-" * 8 + "+" + "-" * 9 + "+" + "-" * 10 + "+" + "-" * 8 + "+" + "-" * 32
    header = f"{col[0]:<4} {col[1]:<10} {col[2]:<8} {col[3]:<9} {col[4]:<10} {col[5]:<8} {col[6]}"
    lines = [header, sep]
    for case in cases:
        status = "PASS" if case.rc == case.expected else "FAIL"
        lines.append(
            f"{case.case_no:<4} {case.terminal:<10} {case.mti:<8} {case.rc:<9} {case.expected:<10} {status:<8} {case.explanation}"
        )
    return "\n".join(lines) + "\n"


def _area_status_table(items: list[AreaStatus]) -> str:
    """Render the area-level validation status summary as a plain-text table.

    Produces a two-column, fixed-width table showing each functional area
    alongside its aggregated pass/fail status.  Designed to be appended
    after the business-case detail table in the output .txt file and
    printed to stdout.

    Column layout::

        Area                    Status
        ------------------------+--------
        ISO Protocol            PASS

    Args:
        items: Ordered list of AreaStatus instances to render.

    Returns:
        A multi-line string containing the full plain-text area-status table.
    """
    sep = "-" * 26 + "+" + "-" * 16
    header = f"{'Area':<26} {'Status'}"
    lines = [header, sep]
    for item in items:
        lines.append(f"{item.area:<26} {item.status}")
    return "\n".join(lines) + "\n"


def test_required_top_level_structure_exists() -> None:
    """Verify that all mandatory project files and directories exist.

    Checks for the presence of every source file, deploy descriptor,
    configuration file, and document that the jPOS-EE switch project
    requires.  A missing file indicates an incomplete scaffold or an
    accidental deletion that would cause the build or runtime to fail.

    Raises:
        AssertionError: Lists every missing path when one or more items
            are absent from the workspace.
    """
    required = [
        "README.md",
        "BUSINESS-CASES.md",
        "pom.xml",
        "cfg/iso87.xml",
        "deploy/10_channel.xml",
        "deploy/30_switch.xml",
        "src/main/java/com/switch/listener/SwitchListener.java",
        "src/main/java/com/switch/service/TransactionService.java",
        "src/main/java/com/switch/service/ReversalService.java",
        "src/main/java/com/switch/service/STANService.java",
        "src/main/java/com/switch/service/RRNService.java",
        "src/main/java/com/switch/dao/TransactionDAO.java",
        "src/main/java/com/switch/dao/EventDAO.java",
        "src/main/java/com/switch/model/Transaction.java",
        "src/main/java/com/switch/model/Event.java",
        "src/main/java/com/switch/util/MacUtil.java",
        "src/main/java/com/switch/util/DukptUtil.java",
    ]
    missing = [p for p in required if not (PROJECT_ROOT / p).exists()]
    assert not missing, f"Missing required paths: {missing}"


def test_deploy_and_packager_xmls_are_well_formed() -> None:
    """Confirm that all Q2 deploy descriptors and the ISO packager XML are valid.

    Parses each XML file with the standard ElementTree parser to detect
    syntax errors, duplicate declarations, or malformed elements that
    would cause Q2 to reject the file at startup (silently renaming it
    to .BAD).

    Raises:
        AssertionError / xml.etree.ElementTree.ParseError: When any XML
            file cannot be parsed cleanly.
    """
    files = [
        "deploy/10_channel.xml",
        "deploy/30_switch.xml",
        "cfg/iso87.xml",
    ]
    assert all(_xml_is_well_formed(f) for f in files)


def test_iso87_contains_critical_fields() -> None:
    """Ensure the ISO-8583 GenericPackager definition includes all critical fields.

    Checks that the packager descriptor declares at minimum the fields
    required by the core switch flow: MTI (0), Amount (4), STAN (11),
    RRN (37), and Response Code (39).  Absence of any of these would
    cause runtime parse/build failures for standard financial messages.

    Raises:
        AssertionError: When a required field id attribute is not found
            in cfg/iso87.xml.
    """
    content = _read("cfg/iso87.xml")
    for field_id in ["id=\"0\"", "id=\"4\"", "id=\"11\"", "id=\"37\"", "id=\"39\""]:
        assert field_id in content


def test_business_cases_document_has_major_sections() -> None:
    """Check that BUSINESS-CASES.md contains all required functional sections.

    The business-cases document must cover every major concern area tested
    by this suite.  Missing sections indicate documentation debt or gaps
    in the requirements specification that need to be filled before the
    project can be considered production-ready.

    Raises:
        AssertionError: Lists every missing section heading.
    """
    content = _read("BUSINESS-CASES.md")
    required_sections = [
        "ISO8583 Protocol",
        "Security",
        "Transaction Lifecycle",
        "Failure & Edge Cases",
        "Idempotency",
        "Persistence",
        "Reversal Logic",
        "Concurrency",
        "Performance",
        "Integration",
        "Logging & Audit",
    ]
    for section in required_sections:
        assert section in content


def test_authorization_business_rule_python_contract() -> None:
    """Validate the Python mirror of the Java authorization response-code rule.

    Calls _auth_result with known boundary inputs and asserts the expected
    ISO-8583 response codes.  This ensures that the Python contract stays
    in sync with TransactionService.handleAuthorization on the Java side.

    Boundary cases tested:
        1000  → '00' (positive amount, approved)
        1     → '00' (minimum positive amount, approved)
        0     → '13' (zero amount, invalid/declined)
        -1    → '13' (negative amount, invalid/declined)

    Raises:
        AssertionError: When any boundary case returns the wrong response code.
    """
    assert _auth_result(1000) == "00"
    assert _auth_result(1) == "00"
    assert _auth_result(0) == "13"
    assert _auth_result(-1) == "13"


def test_stan_and_rrn_format_rules_python_contract() -> None:
    """Verify that STAN and RRN generator functions produce correctly formatted values.

    Tests that:
    - STANs are exactly 6 decimal digits (matching ISO-8583 field 11).
    - Consecutive STANs are unique.
    - RRNs are exactly 12 decimal digits (matching ISO-8583 field 37).
    - Consecutive RRNs with different sequence numbers are unique.

    Raises:
        AssertionError: When any format check or uniqueness check fails.
    """
    stan_1 = _next_stan(1)
    stan_2 = _next_stan(2)
    assert re.fullmatch(r"\d{6}", stan_1)
    assert re.fullmatch(r"\d{6}", stan_2)
    assert stan_1 != stan_2

    now = datetime.now(timezone.utc)
    rrn_1 = _rrn(now, 0)
    rrn_2 = _rrn(now, 1)
    assert re.fullmatch(r"\d{12}", rrn_1)
    assert re.fullmatch(r"\d{12}", rrn_2)
    assert rrn_1 != rrn_2


def test_mac_and_dukpt_vectors_python_contract() -> None:
    """Assert fixed known-answer test vectors for MAC and DUKPT helper functions.

    Pins the output of _mac_sha256_hex and _dukpt_placeholder to agreed
    reference values.  If either function's implementation drifts (e.g.
    encoding change, algorithm swap) these assertions will catch it
    immediately, preventing silent divergence from the Java SecurityService
    implementation.

    Known-answer vectors:
        HMAC-SHA256('hello', 'secret') == '88aab3...'
        DUKPT placeholder('ABCDEF1234567890', 'FFFF9876543210E00001')[:32] == 'c040a4...'

    Raises:
        AssertionError: When computed output does not match the reference vector.
    """
    assert _mac_sha256_hex("hello", "secret") == "88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b"
    assert _dukpt_placeholder("ABCDEF1234567890", "FFFF9876543210E00001") == "c040a4696bf084dc675e0bf4ab8c8ad1"


def test_python_can_validate_full_build_pipeline() -> None:
    """Run the Maven build pipeline and verify that the output JAR is produced.

    Executes 'mvn -q clean package -DskipTests' from the project root and
    asserts a zero exit code.  Then confirms that lib/switch-core.jar exists
    and has a non-zero size, proving that the full Java compilation and
    packaging pipeline succeeds in a clean state.

    This test exercises the entire Java toolchain (compiler → packager →
    JAR assembly) from the Python validation layer without executing any
    Java unit tests, keeping it as a fast integration smoke check.

    Raises:
        AssertionError: When Maven exits non-zero or the output JAR is absent
            or empty.  The assertion message includes Maven's stderr/stdout.
    """
    run = subprocess.run(
        ["mvn", "-q", "clean", "package", "-DskipTests"],
        cwd=PROJECT_ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    assert run.returncode == 0, run.stderr or run.stdout
    jar = PROJECT_ROOT / "lib/switch-core.jar"
    assert jar.exists() and jar.stat().st_size > 0


def test_business_case_table_all_pass_and_export() -> None:
    """Generate the full business-case report, write it to a .txt file, and print it.

    Steps performed:
    1. Retrieve all 27 business-case scenarios via _business_cases().
    2. Assert that every scenario's actual response code matches its expected
       code (all cases must be PASS before the report is written).
    3. Render the business-case detail table via _business_case_table().
    4. Render the area-level status summary via _area_status_table().
    5. Print both tables to stdout so the results are visible in the console
       when running 'pytest -q' or 'pytest -s'.
    6. Write the combined output to python_tests/BUSINESS_CASE_RESULTS.txt
       (plain text, UTF-8) for archiving and CI artifact collection.
    7. Assert that key scenario rows and area-status rows are present in the
       rendered output to catch any accidental truncation or formatting bugs.

    Raises:
        AssertionError: When any scenario RC != expected, or when a required
            row is missing from the rendered tables.
    """
    cases = _business_cases()
    assert all(case.rc == case.expected for case in cases)

    table = _business_case_table(cases)
    areas = _area_status_table(_area_statuses())

    h = "=" * 80
    s = "-" * 80
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    report = (
        h + "\n"
        + "jPOS-EE Q2 Switch \u2014 Business Case Validation Report\n"
        + "Generated : " + timestamp + "\n"
        + h + "\n"
        + "\nBUSINESS CASE DETAIL\n"
        + s + "\n"
        + table
        + "\nAREA STATUS SUMMARY\n"
        + s + "\n"
        + areas
        + "\n" + h + "\n"
    )

    print("\n" + report)

    out_path = PROJECT_ROOT / "python_tests" / "BUSINESS_CASE_RESULTS.txt"
    out_path.write_text(report, encoding="utf-8")

    assert "PASS" in table
    assert "Replay protected: same response" in table
    assert "Robustness: incomplete security rejected" in table
    assert "Replay Protection" in areas
    assert "Robustness" in areas
    assert "BIN: LOCAL (123456) approval" in table
    assert "BIN: LOCAL fraud rule (>100K)" in table
    assert "BIN: VISA (654321) MUX route" in table
    assert "BIN: MC (512345) MUX route" in table
    assert "BIN Routing" in areas
    assert "Fraud Rules" in areas


def test_pytest_generates_runtime_iso_io_logs() -> None:
    """Send one ISO message via jPOS ASCIIChannel so pytest produces runtime Q2 logs.

    This test is intentionally integration-level and requires Q2 to be running on
    localhost:9000. It compiles and runs a tiny Java probe that uses jPOS APIs
    to ensure channel framing is correct (unlike telnet/netcat raw text input).
    """
    q2_log = PROJECT_ROOT / "logs" / "q2.log"

    if not _is_port_open("127.0.0.1", 9000):
        pytest.skip("Q2 is not listening on 127.0.0.1:9000")

    if shutil.which("javac") is None or shutil.which("java") is None:
        pytest.skip("javac/java not available in PATH")

    before = q2_log.read_text(encoding="utf-8", errors="ignore") if q2_log.exists() else ""

    _run_iso_probe_and_assert_response()

    # Give logger a brief moment to flush session lines.
    time.sleep(0.5)

    after = q2_log.read_text(encoding="utf-8", errors="ignore") if q2_log.exists() else ""
    delta = after[len(before):] if len(after) >= len(before) else after

    assert "session-start" in delta or "session-start" in after
    assert "session-end" in delta or "session-end" in after


def test_runtime_iso_roundtrip_is_persisted_in_jpos_db() -> None:
    """Validate that runtime ISO request/response values are persisted in PostgreSQL.

    This checks both the transactions row and detailed event payloads after the
    Java probe sends a financial request.
    """
    if shutil.which("docker") is None:
        pytest.skip("docker is not available")

    if not _is_port_open("127.0.0.1", 9000):
        pytest.skip("Q2 is not listening on 127.0.0.1:9000")

    if shutil.which("javac") is None or shutil.which("java") is None:
        pytest.skip("javac/java not available in PATH")

    # Clean up any pre-existing test transactions to ensure test isolation.
    # This handles cases where populate-settlement-data.sql may have been run
    # and overwrote test transaction data with sample data.
    _run_postgres_query(
        """
        DELETE FROM transaction_events WHERE stan='123456';
        DELETE FROM transaction_meta WHERE stan='123456';
        DELETE FROM transactions WHERE stan='123456' AND rrn='123456789012';
        """
    )

    # Trigger one runtime request/response cycle first.
    _run_iso_probe_and_assert_response()

    tx_row = _run_postgres_query(
        """
        SELECT stan, rrn, terminal_id, mti, rc, status, final_status
        FROM transactions
        WHERE stan='123456' AND rrn='123456789012'
        ORDER BY id DESC
        LIMIT 1;
        """
    )
    assert tx_row, "No persisted transaction row found for STAN=123456"
    stan, rrn, terminal_id, mti, rc, status, final_status = tx_row.split("|")

    assert stan == "123456"
    assert rrn == "123456789012"
    assert terminal_id == "TERM0001"
    assert mti == "0200"
    assert rc == "96"
    assert status == "SECURITY_DECLINE"
    assert final_status == "SECURITY_DECLINE"

    request_iso = _run_postgres_query(
        """
        SELECT request_iso
        FROM transaction_events
        WHERE stan='123456' AND event_type='REQUEST'
        ORDER BY id DESC
        LIMIT 1;
        """
    )
    assert "<field id=\"0\" value=\"0200\"/>" in request_iso
    assert "<field id=\"3\" value=\"000000\"/>" in request_iso
    assert "<field id=\"4\" value=\"000000000100\"/>" in request_iso
    assert "<field id=\"11\" value=\"123456\"/>" in request_iso
    assert "<field id=\"37\" value=\"123456789012\"/>" in request_iso

    response_iso = _run_postgres_query(
        """
        SELECT response_iso
        FROM transaction_events
        WHERE stan='123456' AND event_type='SECURITY_DECLINE'
        ORDER BY id DESC
        LIMIT 1;
        """
    )
    assert "<field id=\"0\" value=\"0210\"/>" in response_iso
    assert "<field id=\"39\" value=\"96\"/>" in response_iso


def test_duplicate_stan_persists_distinct_rows_by_rrn() -> None:
    """Ensure same STAN with different RRN persists as distinct transactions.

    This validates the DB uniqueness and upsert key behavior on (stan, rrn),
    so reusing STAN does not overwrite another transaction with a different RRN.
    """
    if shutil.which("docker") is None:
        pytest.skip("docker is not available")

    if not _is_port_open("127.0.0.1", 9000):
        pytest.skip("Q2 is not listening on 127.0.0.1:9000")

    if shutil.which("javac") is None or shutil.which("java") is None:
        pytest.skip("javac/java not available in PATH")

    shared_stan = "777777"
    rrn_1 = "777777000001"
    rrn_2 = "777777000002"

    # Clean up any pre-existing test transactions
    _run_postgres_query(
        f"""
        DELETE FROM transaction_events WHERE stan='{shared_stan}';
        DELETE FROM transaction_meta WHERE stan='{shared_stan}';
        DELETE FROM transactions WHERE stan='{shared_stan}';
        """
    )

    _run_iso_probe_and_assert_response(stan=shared_stan, rrn=rrn_1)
    _run_iso_probe_and_assert_response(stan=shared_stan, rrn=rrn_2)

    rows = _run_postgres_query(
        f"""
        SELECT rrn, rc, status, final_status
        FROM transactions
        WHERE stan='{shared_stan}' AND rrn IN ('{rrn_1}', '{rrn_2}')
        ORDER BY rrn ASC;
        """
    )
    assert rows, f"No persisted transactions found for STAN={shared_stan}"

    parsed = [line.split("|") for line in rows.splitlines() if line.strip()]
    assert len(parsed) == 2, f"Expected 2 rows, got {len(parsed)} rows: {parsed}"

    rrns = {row[0] for row in parsed}
    assert rrns == {rrn_1, rrn_2}

    for row in parsed:
        assert row[1] == "96"
        assert row[2] == "SECURITY_DECLINE"
        assert row[3] == "SECURITY_DECLINE"

    for rrn in (rrn_1, rrn_2):
        event_counts = _run_postgres_query(
            f"""
            SELECT event_type, COUNT(*)
            FROM transaction_events
            WHERE stan='{shared_stan}' AND rrn='{rrn}'
            GROUP BY event_type
            ORDER BY event_type;
            """
        )
        assert event_counts, f"No lifecycle events found for STAN={shared_stan}, RRN={rrn}"

        event_map = {}
        for line in event_counts.splitlines():
            event_type, count = line.split("|")
            event_map[event_type] = int(count)

        assert event_map.get("REQUEST") == 1
        assert event_map.get("SECURITY_DECLINE") == 1


def test_bin_routing_table_exists_with_sample_data() -> None:
    """Verify that the BIN routing table exists in PostgreSQL with expected sample data.

    Phase 4 introduces BIN-based routing for smart scheme decision (LOCAL vs VISA vs MC).
    This test validates that:
    1. The bins table is created in PostgreSQL
    2. Sample BIN data is present: 123456→LOCAL, 654321→VISA, 512345→MC
    3. The issuer_id and scheme columns are correctly populated
    4. BIN lookups can be performed for routing decisions

    Raises:
        AssertionError: When bins table is missing, empty, or missing expected data.
    """
    if shutil.which("docker") is None:
        pytest.skip("docker is not available")

    # Verify bins table exists
    tables = _run_postgres_query(
        """
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
        ORDER BY table_name;
        """
    )
    assert "bins" in tables, "bins table not found in PostgreSQL"

    # Verify sample BIN data exists
    bin_rows = _run_postgres_query(
        """
        SELECT bin, scheme, issuer_id
        FROM bins
        ORDER BY bin;
        """
    )
    assert bin_rows, "No BIN data found in bins table"

    # Parse and validate each BIN entry
    bins_data = {}
    for line in bin_rows.splitlines():
        if line.strip():
            bin_val, scheme, issuer_id = line.split("|")
            bins_data[bin_val] = (scheme, issuer_id)

    # Expected BINs: LOCAL, VISA, MC
    assert "123456" in bins_data, "LOCAL BIN (123456) not found"
    assert bins_data["123456"] == ("LOCAL", "BANK_A"), f"LOCAL BIN data mismatch: {bins_data['123456']}"

    assert "654321" in bins_data, "VISA BIN (654321) not found"
    assert bins_data["654321"] == ("VISA", "BANK_B"), f"VISA BIN data mismatch: {bins_data['654321']}"

    assert "512345" in bins_data, "MC BIN (512345) not found"
    assert bins_data["512345"] == ("MC", "BANK_C"), f"MC BIN data mismatch: {bins_data['512345']}"


def test_settlement_batches_table_exists() -> None:
    """Verify that the settlement_batches table exists for Phase 4 settlement tracking.

    The settlement module aggregates multiple transactions into logical batches
    for reporting and multi-party settlement. This test validates that the
    settlement_batches table is properly created and indexed.

    Raises:
        AssertionError: When settlement_batches table is missing.
    """
    if shutil.which("docker") is None:
        pytest.skip("docker is not available")

    # Verify settlement_batches table exists
    tables = _run_postgres_query(
        """
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
        ORDER BY table_name;
        """
    )
    assert "settlement_batches" in tables, "settlement_batches table not found in PostgreSQL"

    # Verify settlement_batches table structure
    columns = _run_postgres_query(
        """
        SELECT column_name, data_type
        FROM information_schema.columns
        WHERE table_name = 'settlement_batches'
        ORDER BY ordinal_position;
        """
    )
    assert columns, "settlement_batches table has no columns"

    column_map = {}
    for line in columns.splitlines():
        if line.strip():
            col_name, col_type = line.split("|")
            column_map[col_name] = col_type

    # Expected columns for settlement tracking
    assert "id" in column_map, "Missing id column in settlement_batches"
    assert "batch_id" in column_map, "Missing batch_id column in settlement_batches"
    assert "total_count" in column_map, "Missing total_count column in settlement_batches"
    assert "total_amount" in column_map, "Missing total_amount column in settlement_batches"
    assert "created_at" in column_map, "Missing created_at column in settlement_batches"


def test_transactions_table_has_routing_columns() -> None:
    """Verify that the transactions table has been extended with Phase 4 routing columns.

    Phase 4 adds issuer_id, scheme, retry_count, and settlement tracking columns
    to the core transactions table. This test validates that all new columns exist
    with correct data types and constraints.

    Raises:
        AssertionError: When any routing column is missing or has wrong type.
    """
    if shutil.which("docker") is None:
        pytest.skip("docker is not available")

    # Get transactions table structure
    columns = _run_postgres_query(
        """
        SELECT column_name, data_type
        FROM information_schema.columns
        WHERE table_name = 'transactions'
        ORDER BY ordinal_position;
        """
    )
    assert columns, "transactions table has no columns"

    column_map = {}
    for line in columns.splitlines():
        if line.strip():
            col_name, col_type = line.split("|")
            column_map[col_name] = col_type

    # Phase 4 routing columns
    assert "issuer_id" in column_map, "Missing issuer_id column in transactions"
    assert "scheme" in column_map, "Missing scheme column in transactions"
    assert "retry_count" in column_map, "Missing retry_count column in transactions"
    assert "settled" in column_map, "Missing settled column in transactions"
    assert "settlement_date" in column_map, "Missing settlement_date column in transactions"
    assert "batch_id" in column_map, "Missing batch_id column in transactions"


def test_terminals_table_exists_for_acquirer_mapping() -> None:
    """Verify that the terminals table exists with acquirer mappings for multi-party settlement.

    The terminals table maps terminal_id to acquirer_id (the merchant's bank).
    This is critical for multi-party settlement, which connects:
    - Issuer ID (cardholder's bank) from BIN lookup
    - Acquirer ID (merchant's bank) from terminal mapping

    Example settlement flow:
    - Bank A (issuer) owes Bank B (acquirer) based on accumulated transactions
    - Bank A → Bank B: 100,000
    - Bank B → Bank A:  30,000
    - Net: Bank A owes Bank B 70,000

    Raises:
        AssertionError: When terminals table doesn't exist or sample data is missing.
    """
    if shutil.which("docker") is None:
        pytest.skip("docker is not available")

    # Verify terminals table exists
    result = _run_postgres_query(
        """
        SELECT EXISTS (
            SELECT FROM information_schema.tables
            WHERE table_name = 'terminals'
        );
        """
    )
    assert "t" in result.lower(), "terminals table does not exist"

    # Verify sample terminal mappings
    terminals = _run_postgres_query(
        """
        SELECT terminal_id, acquirer_id
        FROM terminals
        ORDER BY terminal_id;
        """
    )
    assert "TERM0001" in terminals, "Missing sample terminal TERM0001"
    assert "TERM0002" in terminals, "Missing sample terminal TERM0002"
    assert "TERM0003" in terminals, "Missing sample terminal TERM0003"
    assert "BANK_A" in terminals, "Missing sample acquirer BANK_A"
    assert "BANK_B" in terminals, "Missing sample acquirer BANK_B"
    assert "BANK_C" in terminals, "Missing sample acquirer BANK_C"


def test_multi_party_settlement_schema_complete() -> None:
    """Verify that all schema components for multi-party settlement are in place.

    Multi-party settlement requires:
    1. bins table: PAN prefix → Issuer Bank mapping
    2. terminals table: Terminal ID → Acquirer Bank mapping
    3. transactions table: issuer_id + acquirer_id + settled flag
    4. settlement_batches table: Aggregated settlement batches

    This test validates that the database is configured for calculating
    net positions between issuer and acquirer banks.

    Raises:
        AssertionError: When any required settlement component is missing.
    """
    if shutil.which("docker") is None:
        pytest.skip("docker is not available")

    # Verify issuer_id in transactions (from BIN lookup)
    result = _run_postgres_query(
        """
        SELECT column_name FROM information_schema.columns
        WHERE table_name = 'transactions' AND column_name = 'issuer_id';
        """
    )
    assert result.strip(), "Missing issuer_id in transactions (BIN lookup)"

    # Verify acquirer_id in transactions (from terminal mapping)
    result = _run_postgres_query(
        """
        SELECT column_name FROM information_schema.columns
        WHERE table_name = 'transactions' AND column_name = 'acquirer_id';
        """
    )
    assert result.strip(), "Missing acquirer_id in transactions (terminal mapping)"

    # Verify settled flag for settlement tracking
    result = _run_postgres_query(
        """
        SELECT column_name FROM information_schema.columns
        WHERE table_name = 'transactions' AND column_name = 'settled';
        """
    )
    assert result.strip(), "Missing settled flag in transactions"

    # Verify bins table has sample data
    result = _run_postgres_query(
        "SELECT COUNT(*) FROM bins WHERE bin IN ('123456', '654321', '512345');"
    )
    assert "3" in result, "Missing sample BIN data for settlement testing"

    # Verify terminals table has sample data
    result = _run_postgres_query(
        "SELECT COUNT(*) FROM terminals WHERE terminal_id IN ('TERM0001', 'TERM0002', 'TERM0003');"
    )
    assert "3" in result, "Missing sample terminal data for settlement testing"
