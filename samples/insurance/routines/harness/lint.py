"""Lint the additive harness: IFOX00 columns for the driver, pyflakes/pycodestyle for Python.

Mirrors samples/insurance/tools/lint.py for routines/driver/*.asm (that frozen script only
covers src/ and copy/) and runs flake8 over routines/ when it is installed.
"""

from pathlib import Path
import subprocess
import sys

ROUTINES = Path(__file__).resolve().parents[1]


def asm_errors(path: Path) -> list[str]:
    errors = []
    labels: set[str] = set()
    lines = path.read_text().splitlines()
    for number, line in enumerate(lines, 1):
        if "\t" in line or not line.isascii():
            errors.append(f"{path.name}:{number}: tabs/non-ASCII")
        if len(line) > 72:
            errors.append(f"{path.name}:{number}: exceeds column 72")
        if line.startswith("*") or not line.strip():
            continue
        label = line[:8].strip()
        if label and not label.startswith("&"):
            if label in labels:
                errors.append(f"{path.name}:{number}: duplicate {label}")
            labels.add(label)
        if len(line) > 71 and line[71] != " " and number == len(lines):
            errors.append(f"{path.name}:{number}: dangling continuation")
    return errors


def main() -> None:
    errors = []
    for path in sorted((ROUTINES / "driver").glob("*.asm")):
        errors.extend(asm_errors(path))
    if errors:
        raise SystemExit("\n".join(errors))
    print("routines/driver IFOX00 columns and local labels: PASS")
    flake8 = subprocess.run([sys.executable, "-m", "flake8", "--version"], capture_output=True)
    if flake8.returncode != 0:
        print("flake8 not installed: skipping Python style check")
        return
    check = subprocess.run([sys.executable, "-m", "flake8", "--max-line-length=100",
                            "--extend-ignore=E501", str(ROUTINES)])
    if check.returncode != 0:
        raise SystemExit("flake8 reported findings")
    print("routines/ flake8 (pyflakes + pycodestyle): PASS")


if __name__ == "__main__":
    main()
