"""Export seven IFOX00 decks and report every cols 1-72 difference from as370."""

import argparse
from itertools import zip_longest
from pathlib import Path

from deck_compare import normalized
from tk5 import Dataset, Guest, ROOT
from tk5_validate import save, sha


def cards(raw: bytes) -> tuple[list[bytes], list[bytes]]:
    return normalized(raw), [raw[-80:]]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--root", type=Path, default=Path("/home/ubuntu/mvs-demo"))
    args = parser.parse_args()
    with Guest(args.evidence, args.root) as guest:
        names = [path.stem for path in sorted((ROOT / "src").glob("*.asm"))]
        observed = guest.transfer("decks", [
            Dataset(f"{args.prefix}.OBJ({name})", 80, 3200) for name in names
        ], "out")
        reports = []
        for name, raw in zip(names, observed):
            host = (ROOT / "build" / (name + ".obj")).read_bytes()
            left, left_end = cards(raw)
            right, right_end = cards(host)
            (guest.evidence / (name + ".ifox.obj")).write_bytes(raw)
            (guest.evidence / (name + ".as370.obj")).write_bytes(host)
            differences = []
            for index, (a, b) in enumerate(zip_longest(left, right, fillvalue=b""), 1):
                if a != b:
                    differences.append({
                        "card": index, "ifox_bytes": a.hex(), "as370_bytes": b.hex(),
                        "different_columns": [i for i, (x, y) in enumerate(
                            zip_longest(a, b, fillvalue=None), 1) if x != y],
                    })
            reports.append({
                "module": name, "identical": not differences,
                "ifox_non_end_cards": len(left), "as370_non_end_cards": len(right),
                "compared_columns": "1-72", "excluded_sequence_columns": "73-80",
                "ifox_end_metadata": left_end[0].hex(),
                "as370_end_metadata": right_end[0].hex(),
                "ifox_sha256": sha(raw), "as370_sha256": sha(host),
                "differences": differences,
                "scope": "Object build comparison, separate from execution evidence",
            })
        save(guest.evidence / "deck-comparison.json", reports)
        for report in reports:
            print(report["module"], "MATCH" if report["identical"] else "DIFF",
                  report["ifox_non_end_cards"], "non-END cards", flush=True)
        if any(not report["identical"] for report in reports):
            raise SystemExit(1)


if __name__ == "__main__":
    main()
