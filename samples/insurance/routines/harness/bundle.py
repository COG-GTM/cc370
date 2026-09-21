"""Package observed evidence with receipts and provenance hashes.

    python3 bundle.py --evidence /path/to/routines-evidence --out ../evidence \
                      --archive /path/to/bundle.tar.gz

Two outputs:
  * a compact, committable summary under routines/evidence/ (run/comparison/jobs JSON,
    decoded observations, the raw capture, receipts + provenance), and
  * a full tar.gz of every raw guest artifact (JCL, spool, AWS tapes, datasets) whose
    per-file SHA-256 is listed in RECEIPTS.json so the archive can be verified offline.

Receipts describe what was observed; they never restate expectations.
"""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tarfile

from layout import INSURANCE, ROUTINES, sha256

ROUTINE_RUNS = ("ifox-iewl", "as370-iewl", "as370-ld370")
JOB_RUNS = tuple("jobs-" + b for b in ROUTINE_RUNS)
COMPACT = ("run.json", "comparison.json", "observed.json", "jobs.json",
           "decks/deck-comparison.json", "host/build.json",
           "provenance/build.json", "provenance/guest.json")
ROOT = INSURANCE.parents[1]


def tree(paths: list[Path], base: Path) -> dict[str, str]:
    return {str(p.relative_to(base)): sha256(p.read_bytes()) for p in sorted(paths) if p.is_file()}


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True,
                          check=True).stdout.strip()


def hercules_version(install: Path) -> str:
    binary = install / "hercules-install" / "bin" / "hercules"
    if not binary.exists():
        return "not found"
    out = subprocess.run([str(binary), "--version"], capture_output=True, text=True)
    return next((line for line in out.stdout.splitlines() if "version" in line.lower()), "unknown")


def provenance(evidence: Path, maclib: Path) -> dict:
    mvs = evidence.parent
    hyperion = mvs / "hyperion"
    tk5 = mvs / "mvs-tk5"
    guest_json = next(evidence.glob("jobs-*/provenance/guest.json"), None)
    guest = json.loads(guest_json.read_text()) if guest_json else {}
    return {
        "repository_commit": git("rev-parse", "HEAD"),
        "repository_branch": git("rev-parse", "--abbrev-ref", "HEAD"),
        "frozen_baseline": "28790e2a3f343980f15d8bffd55e5811fbfa2ee5",
        "source_sha256": tree(list((INSURANCE / "src").glob("*.asm")), INSURANCE),
        "copy_sha256": tree(list((INSURANCE / "copy").iterdir()), INSURANCE),
        "driver_sha256": tree([ROUTINES / "driver" / "INSDRV.asm"], INSURANCE),
        "harness_sha256": tree(list((ROUTINES / "harness").glob("*.py")), INSURANCE),
        "rates_sha256": tree([INSURANCE / "golden" / "v1" / "rates.json"], INSURANCE),
        "fixtures_sha256": tree(list((ROUTINES / "fixtures").rglob("*")), INSURANCE),
        "contract_sha256": tree(list((ROUTINES / "contract").glob("*.json")), INSURANCE),
        "host_toolchain_sha256": tree([ROOT / "as370" / "as370", ROOT / "ld370" / "ld370",
                                       ROOT / "file370" / "file370"], ROOT),
        "macro_library": {"path": str(maclib), "members": len(list(maclib.iterdir())),
                          "tree_sha256": sha256(json.dumps(tree(list(maclib.iterdir()), maclib),
                                                           sort_keys=True).encode())},
        "emulator": {"hercules": hercules_version(mvs),
                     "hyperion_commit": (subprocess.run(["git", "-C", str(hyperion), "rev-parse",
                                                         "HEAD"], capture_output=True, text=True)
                                         .stdout.strip() if hyperion.exists() else "unknown"),
                     "distribution": guest.get("distribution", "unknown"),
                     "emulator_sha256": guest.get("emulator_sha256", "unknown"),
                     "configuration_sha256": guest.get("configuration_sha256", "unknown")},
        "guest": {"install": str(tk5), "namespace": "mvs-smoke",
                  "note": "one exclusive writer per private DASD set (guest.lock/run.lock)"},
    }


def summarize(evidence: Path) -> dict:
    runs: dict = {}
    for name in ROUTINE_RUNS:
        run = evidence / name / "run.json"
        if run.exists():
            data = json.loads(run.read_text())
            keys = ("backend", "prefix", "capture_records", "capture_sha256", "cases_passed",
                    "findings", "comparison_passed", "fixture_manifest_sha256", "scope")
            runs[name] = {k: data[k] for k in keys}
            runs[name]["jobs"] = data["jobs"]
    for name in JOB_RUNS:
        jobs = evidence / name / "jobs.json"
        if jobs.exists():
            data = json.loads(jobs.read_text())
            runs[name] = {k: data[k] for k in ("backend", "prefix", "passed", "measured_scope")}
            runs[name]["job_ids"] = job_ids(data["phases"])
    return runs


def derivation(evidence: Path) -> dict:
    """Which harness decoded/compared each retained capture, versus what ran at capture time.

    run.json is the guest receipt written when the capture was taken and is never rewritten.
    comparison.json / observed.json are host-derived and may be regenerated later by a revised
    comparator/decoder; their harness_identity hashes name the code that produced them. The
    *.orig.json files, when present, are the artifacts written at capture time, kept verbatim.
    """
    out: dict = {}
    for name in ROUTINE_RUNS:
        run = evidence / name
        if not (run / "run.json").exists():
            continue
        receipt = json.loads((run / "run.json").read_text())
        entry = {"guest_receipt": {"file": "run.json", "sha256": sha256((run / "run.json").read_bytes()),
                                   "capture_sha256": receipt["capture_sha256"],
                                   "fixture_manifest_sha256_at_run": receipt["fixture_manifest_sha256"],
                                   "cases_sha256_at_run": receipt["cases_sha256"]},
                 "derived": {}, "retained_originals": {}}
        for derived in ("comparison.json", "observed.json"):
            if (run / derived).exists():
                data = json.loads((run / derived).read_text())
                entry["derived"][derived] = {
                    "schema": data.get("schema"),
                    "capture_sha256": data.get("capture_sha256"),
                    "fixture_manifest_sha256": data.get("fixture_manifest_sha256"),
                    "harness_identity": data.get("harness_identity"),
                    "same_capture_as_receipt": data.get("capture_sha256") == receipt["capture_sha256"],
                    "same_manifest_as_run": (data.get("fixture_manifest_sha256")
                                             == receipt["fixture_manifest_sha256"]),
                }
            orig = run / derived.replace(".json", ".orig.json")
            if orig.exists():
                entry["retained_originals"][orig.name] = sha256(orig.read_bytes())
        out[name] = entry
    out["note"] = ("the guest executed the driver against cases.bin (cases_sha256_at_run) and wrote "
                   "run.json once; comparison.json/observed.json were (re)derived on the host from "
                   "the retained capture by the harness named in harness_identity, which may postdate "
                   "the run. same_manifest_as_run=false means only manifest metadata changed since; "
                   "cases_sha256 is checked by contract.validate_run.")
    return out


def job_ids(node) -> list[str]:
    found: list[str] = []
    if isinstance(node, dict):
        if "job_id" in node and "steps" in node:
            found.append(f'{node.get("job", "?")}={node["job_id"]}:'
                         + ",".join(f"{s}={rc}" for s, rc in node["steps"]))
        for value in node.values():
            found.extend(job_ids(value))
    elif isinstance(node, list):
        for value in node:
            found.extend(job_ids(value))
    return found


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=ROUTINES / "evidence")
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--maclib", type=Path, default=Path.home() / "mvs-demo/export/ascii")
    parser.add_argument("--extra", type=Path, nargs="*", default=[])
    args = parser.parse_args()

    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    included = [args.evidence / n for n in ROUTINE_RUNS + JOB_RUNS if (args.evidence / n).exists()]
    for run in included:
        target = out / run.name
        target.mkdir(exist_ok=True)
        for name in COMPACT + ("comparison.orig.json",):
            if (run / name).exists():
                shutil.copy2(run / name, target / name.replace("/", "-"))
        for name in ("run", "smoke"):
            for spool in (run / name).glob("prt*.txt") if (run / name).is_dir() else []:
                shutil.copy2(spool, target / f"{name}-{spool.name}")
    captures = {run.name: sha256((run / "capture.bin").read_bytes())
                for run in included if (run / "capture.bin").exists()}
    if captures:
        first = next(run for run in included if (run / "capture.bin").exists())
        shutil.copy2(first / "capture.bin", out / "capture.bin")
    for extra in args.extra:
        shutil.copy2(extra, out / extra.name)

    receipts = {
        "schema": "insurance-routines-receipts-v1",
        "provenance": provenance(args.evidence, args.maclib),
        "runs": summarize(args.evidence),
        "derivation": derivation(args.evidence),
        "capture_sha256_by_path": captures,
        "captures_identical_across_paths": len(set(captures.values())) == 1 if captures else None,
        "compact_files_sha256": tree([p for p in out.rglob("*") if p.name != "RECEIPTS.json"], out),
        "raw_files_sha256": {run.name: tree(list(run.rglob("*")), run) for run in included},
    }
    (out / "RECEIPTS.json").write_text(json.dumps(receipts, indent=1, sort_keys=True) + "\n")
    print(f"compact evidence: {out} ({len(receipts['compact_files_sha256'])} files)")

    if args.archive:
        with tarfile.open(args.archive, "w:gz") as tar:
            tar.add(out / "RECEIPTS.json", arcname="RECEIPTS.json")
            for run in included:
                tar.add(run, arcname=run.name)
            for extra in args.extra:
                tar.add(extra, arcname=extra.name)
        print(f"archive: {args.archive} sha256 {sha256(args.archive.read_bytes())}")


if __name__ == "__main__":
    main()
