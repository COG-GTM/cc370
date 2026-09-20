"""Fresh three-backend application validation, optionally paused for a live walkthrough."""

import argparse
import json
from pathlib import Path
import subprocess
import sys

from codec import decode
from render_jcl import dataset
from tk5 import ROOT


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--root", type=Path, default=Path("/home/ubuntu/mvs-demo"))
    parser.add_argument("--maclib", type=Path, required=True)
    parser.add_argument("--interactive", action="store_true")
    args = parser.parse_args()
    dataset(args.prefix)
    args.evidence.mkdir(parents=True, exist_ok=False)

    def chapter(title: str) -> None:
        print("\n" + "=" * 74 + "\n" + title + "\n" + "=" * 74, flush=True)
        if args.interactive:
            input("Press Enter to execute this chapter on the real guest: ")

    def run(tool: str, *options: str) -> None:
        command = [sys.executable, str(ROOT / "tools" / tool), *options]
        print("$", " ".join(command), flush=True)
        subprocess.run(command, cwd=ROOT, check=True)

    def location(folder: str, suffix: str) -> list[str]:
        return ["--root", str(args.root), "--evidence", str(args.evidence / folder),
                "--prefix", args.prefix + "." + suffix]

    chapter("1. Host build and exact FB128 / FB40 / FB96 transport")
    subprocess.run(["make", "batch", f"MACLIBS={args.maclib}"], cwd=ROOT, check=True)
    run("tk5.py", "prove", *location("transport", "T"))
    chapter("2. IFOX00 assembles seven modules; IEWL links; INSSMOK executes")
    run("tk5.py", "build", *location("ifox-build", "I"))
    run("tk5_decks.py", *location("decks", "I"))
    for backend, suffix, build in (("ifox-iewl", "I", "ifox-build/build"),
                                   ("as370-iewl", "H", "host-build/link"),
                                   ("as370-ld370", "L", "ld-build/import")):
        if suffix == "H":
            chapter("4. Separately imported as370 decks; real IEWL link and execution")
            run("tk5.py", "host", *location("host-build", suffix))
        elif suffix == "L":
            chapter("5. Host ld370 load modules; binary IEBCOPY import and execution")
            run("tk5_linker.py", *location("ld-build", suffix))
        else:
            chapter("3. Real IFOX00 batch results, five stages and business calculation")
        provenance = args.evidence / (backend + "-provenance")
        run("tk5_provenance.py", "--root", str(args.root), "--output", str(provenance),
            "--build-evidence", str(args.evidence / build), "--backend", backend,
            "--load-prefix", args.prefix + "." + suffix, "--macro-export", str(args.maclib))
        manifests = ["--build-manifest", str(provenance / "build.json"),
                     "--guest-manifest", str(provenance / "guest.json"),
                     "--load-prefix", args.prefix + "." + suffix]
        run("tk5_validate.py", *location(backend, suffix + "R"), *manifests)
        actual = decode((args.evidence / backend / "anchors/resout.bin").read_bytes()[:96], "result")
        print("\nACTUAL A001 / policy", actual["id"], "/ status", actual["status"])
        print("BR-007: half-up(100000 cents * 325 bps * 366 days / 3650000)")
        print(f"  Interest ${actual['interest'] / 100:,.2f}; cash ${actual['cash'] / 100:,.2f}")
        print("BR-008: cash = initial cash + interest + $100 premium")
        print("BR-009/010: cash less 350-bps charge and loan; max(face,cash) less loan")
        print(f"  Charge ${actual['charge'] / 100:,.2f}; surrender ${actual['surrender'] / 100:,.2f}")
        print(f"  Death quote ${actual['death'] / 100:,.2f}; quote does not settle a claim",
              flush=True)
        if args.interactive:
            input("Press Enter for real rejection and recovery controls: ")
        run("tk5_controls.py", *location(backend + "-controls", suffix + "N"),
            "--load-prefix", args.prefix + "." + suffix)
        run("tk5_recovery.py", *location(backend + "-recovery", suffix + "C"), *manifests)
        controls = json.loads((args.evidence / (backend + "-controls/controls.json")).read_text())
        print("ACTUAL REJECTION STATUSES:", controls[-1]["statuses"])
        print("Duplicate / changed replay / older sequence / bad packed digits / bad date.")
        print("Every rejection preserved all 128 persisted bytes.", flush=True)
    chapter("6. Cross-backend outputs")
    for stage in ("anchors", "a", "a-replay", "b", "b-replay"):
        for filename in ("polout.bin", "resout.bin"):
            baseline = (args.evidence / "ifox-iewl" / stage / filename).read_bytes()
            for backend in ("as370-iewl", "as370-ld370"):
                if baseline != (args.evidence / backend / stage / filename).read_bytes():
                    raise ValueError(f"Cross-backend difference: {backend}/{stage}/{filename}")
        print(f"PASS: {stage}, both outputs identical across all three executable paths", flush=True)
    print("PASS: real guest execution, all comparisons, rejections and recovery controls",
          flush=True)


if __name__ == "__main__":
    main()
