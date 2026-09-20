"""Check IFOX00 fixed columns and duplicate local labels before assembly."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    errors = []
    for directory in ["src", "copy"]:
        for path in sorted((ROOT / directory).iterdir()):
            labels: set[str] = set()
            for number, line in enumerate(path.read_text().splitlines(), 1):
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
                if len(line) > 71 and line[71] != " ":
                    if number == len(path.read_text().splitlines()):
                        errors.append(f"{path.name}:{number}: dangling continuation")
    if errors:
        raise SystemExit("\n".join(errors))
    print("IFOX00 columns and local labels: PASS")


if __name__ == "__main__":
    main()
