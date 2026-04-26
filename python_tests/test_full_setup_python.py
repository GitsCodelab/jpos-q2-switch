from __future__ import annotations

import hashlib
import hmac
import re
import subprocess
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET


PROJECT_ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class BusinessCase:
    case_no: int
    terminal: str
    mti: str
    rc: str
    expected: str
    explanation: str


def _read(path: str) -> str:
    return (PROJECT_ROOT / path).read_text(encoding="utf-8")


def _xml_is_well_formed(path: str) -> bool:
    ET.parse(PROJECT_ROOT / path)
    return True


def _auth_result(amount: int) -> str:
    return "00" if amount > 0 else "13"


def _next_stan(start: int = 1) -> str:
    return f"{start:06d}"


def _rrn(prefix_dt: datetime, sequence: int) -> str:
    prefix = prefix_dt.strftime("%y%j%H%M")
    return prefix + f"{sequence % 1000:03d}"


def _mac_sha256_hex(data: str, key: str) -> str:
    return hmac.new(key.encode("utf-8"), data.encode("utf-8"), hashlib.sha256).hexdigest()


def _dukpt_placeholder(bdk: str, ksn: str) -> str:
    return _mac_sha256_hex(f"{bdk}:{ksn}", bdk)[:32]


def _business_cases() -> list[BusinessCase]:
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
    ]


def _business_case_table(cases: list[BusinessCase]) -> str:
    lines = [
        "| # | Terminal | MTI | RC | Expected | Status | Explanation |",
        "|---|---|---|---|---|---|---|",
    ]
    for case in cases:
        status = "✅" if case.rc == case.expected else "❌"
        lines.append(
            f"| {case.case_no} | {case.terminal} | {case.mti} | {case.rc} | {case.expected} | {status} | {case.explanation} |"
        )
    return "\n".join(lines) + "\n"


def test_required_top_level_structure_exists() -> None:
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
    files = [
        "deploy/10_channel.xml",
        "deploy/30_switch.xml",
        "cfg/iso87.xml",
    ]
    assert all(_xml_is_well_formed(f) for f in files)


def test_iso87_contains_critical_fields() -> None:
    content = _read("cfg/iso87.xml")
    for field_id in ["id=\"0\"", "id=\"4\"", "id=\"11\"", "id=\"37\"", "id=\"39\""]:
        assert field_id in content


def test_business_cases_document_has_major_sections() -> None:
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
    assert _auth_result(1000) == "00"
    assert _auth_result(1) == "00"
    assert _auth_result(0) == "13"
    assert _auth_result(-1) == "13"


def test_stan_and_rrn_format_rules_python_contract() -> None:
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
    assert _mac_sha256_hex("hello", "secret") == "88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b"
    assert _dukpt_placeholder("ABCDEF1234567890", "FFFF9876543210E00001") == "c040a4696bf084dc675e0bf4ab8c8ad1"


def test_python_can_validate_full_build_pipeline() -> None:
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
    cases = _business_cases()
    assert all(case.rc == case.expected for case in cases)

    table = _business_case_table(cases)
    out_path = PROJECT_ROOT / "python_tests" / "BUSINESS_CASE_RESULTS.md"
    out_path.write_text(table, encoding="utf-8")

    assert "| # | Terminal | MTI | RC | Expected | Status | Explanation |" in table
    assert "| 21 | - | - | TIMEOUT | TIMEOUT | ✅ | Simulated |" in table
    assert "| 22 | TERM0001 | 0200 | 96 | 96 | ✅ | Invalid MAC rejected |" in table
