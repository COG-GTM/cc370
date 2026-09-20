"""Capture build, guest, tool and source hashes beside raw TK5 job evidence."""

import argparse
import json
from pathlib import Path
import subprocess

from tk5 import ROOT
from tk5_validate import save, sha


def files(directory: Path) -> dict[str, str]:
    return {str(path.relative_to(directory)): sha(path.read_bytes())
            for path in sorted(directory.rglob("*"))
            if path.is_file() and "__pycache__" not in path.parts}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-evidence", type=Path, required=True)
    parser.add_argument("--backend", choices=("ifox-iewl", "as370-iewl", "as370-ld370"),
                        required=True)
    parser.add_argument("--load-prefix", required=True)
    parser.add_argument("--root", type=Path, default=Path("/home/ubuntu/mvs-demo"))
    parser.add_argument("--macro-export", type=Path)
    args = parser.parse_args()
    outcome = json.loads((args.build_evidence / "result.json").read_text())
    if not outcome["passed"]:
        raise ValueError("Build job did not pass")
    args.output.mkdir(parents=True, exist_ok=False)
    build = {
        "backend": args.backend, "load_prefix": args.load_prefix,
        "job": outcome, "raw_job_evidence": files(args.build_evidence),
        "source_commit": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "worktree_status": subprocess.check_output(
            ["git", "status", "--porcelain"], cwd=ROOT, text=True),
        "sources": {folder: files(ROOT / folder) for folder in ("src", "copy", "jcl", "tools")},
        "host_toolchain": {
            name: sha((ROOT.parents[1] / name / name).read_bytes())
            for name in ("as370", "ld370", "file370")},
    }
    if args.backend != "ifox-iewl":
        build["host_build"] = json.loads((ROOT / "build/batch-manifest.json").read_text())
    save(args.output / "build.json", build)
    executable = args.root / "hercules-install/bin/hercules"
    version = subprocess.run([str(executable), "--version"], capture_output=True,
                             text=True, check=True)
    guest = {
        "distribution": "Official TK5 Update 5 / MVS 3.8J",
        "distribution_url": "https://www.prince-webdesign.nl/tk5",
        "guest_archive_sha256": "710d002843631322810a276dd42c793fda458548dc64d86e2914a62db7425f84",
        "emulator_version": version.stdout + version.stderr,
        "emulator_sha256": sha(executable.read_bytes()),
        "configuration_sha256": sha((args.root / "mvs-tk5/conf/tk5.cnf").read_bytes()),
        "sys1_maclib_export": files(args.macro_export or args.root / "bundle/macros"),
        "build_job_id": outcome["job_id"],
    }
    save(args.output / "guest.json", guest)


if __name__ == "__main__":
    main()
