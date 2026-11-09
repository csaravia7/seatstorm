"""CI guard (spec 3.3.1 / ADR-20): forbidden packages must never appear in requirements.txt,
direct or transitive. Also guards the pyproject declaration."""
import re
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = re.compile(r"^(langchain|opentelemetry|traceloop|openlit)", re.IGNORECASE)


def _names(lines):
    for line in lines:
        line = line.strip()
        if not line or line.startswith(("#", "-", "--hash")):
            continue
        yield re.split(r"[<>=!~\[; ]", line, maxsplit=1)[0].replace("_", "-")


def test_requirements_txt_has_no_forbidden_packages():
    names = list(_names((ROOT / "requirements.txt").read_text().splitlines()))
    assert names, "requirements.txt is empty"
    bad = [n for n in names if FORBIDDEN.match(n)]
    assert not bad, f"Forbidden dependencies in requirements.txt: {bad}"


def test_requirements_pins_official_sdks_with_hashes():
    text = (ROOT / "requirements.txt").read_text()
    for pkg in ("anthropic", "openai", "fastapi", "uvicorn", "httpx", "pydantic"):
        assert re.search(rf"^{pkg}==", text, re.MULTILINE), pkg
    assert "--hash=sha256:" in text


def test_pyproject_has_no_forbidden_packages():
    data = tomllib.loads((ROOT / "pyproject.toml").read_text())
    deps = data["project"]["dependencies"] + sum(data["project"].get("optional-dependencies", {}).values(), [])
    assert not [d for d in _names(deps) if FORBIDDEN.match(d)]


def test_guard_detects_forbidden_names():
    assert FORBIDDEN.match("langchain-core") and FORBIDDEN.match("opentelemetry-api")
    assert FORBIDDEN.match("traceloop-sdk") and FORBIDDEN.match("openlit")
    assert not FORBIDDEN.match("anthropic")
