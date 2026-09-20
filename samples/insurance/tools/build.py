"""Build real decks; OS macros must come from explicitly supplied guest exports."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

SAMPLE = Path(__file__).resolve().parents[1]
ROOT = SAMPLE.parents[1]
CORE = ["INSCALC", "INSVAL", "INSPACK", "INSDATE", "INSRATE"]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["core", "batch"])
    parser.add_argument("--maclib", type=Path, action="append", default=[])
    args = parser.parse_args()
    if args.mode == "batch" and not args.maclib:
        parser.error("batch requires --maclib with exact guest SYS1.MACLIB export")
    for directory in args.maclib:
        if not directory.is_absolute() or not directory.is_dir():
            parser.error(f"macro path must be an existing absolute directory: {directory}")
    assembler = ROOT / "as370/as370"
    linker = ROOT / "ld370/ld370"
    # A private executable location makes as370's implicit ../macros absent.
    build = SAMPLE / "build"
    private = build / "hostbin"
    private.mkdir(parents=True, exist_ok=True)
    shadow = build / "macros"
    if shadow.exists():
        parser.error(f"unexpected implicit macro directory: {shadow}")
    local_as = private / "as370"
    local_as.write_bytes(assembler.read_bytes())
    local_as.chmod(0o755)
    env = dict(os.environ, AS370_MACLIB="", ASMDATE="09/20/26", ASMTIME="00.00")
    include = ["-I", str(SAMPLE / "copy")]
    for directory in args.maclib:
        include += ["-I", str(directory)]
    names = CORE + ["INSSMOK"] + (["INSBAT"] if args.mode == "batch" else [])
    manifest: dict[str, object] = {
        "scope": "host build only; not observed MVS execution",
        "source_commit": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip(),
        "toolchain": {str(p.relative_to(ROOT)): digest(p) for p in [assembler, linker]},
        "sources": {},
        "macros": {},
        "outputs": {},
    }
    sources = {
        str(p.relative_to(SAMPLE)): digest(p)
        for folder in ["src", "copy"]
        for p in sorted((SAMPLE / folder).iterdir())
        if p.is_file()
    }
    manifest["sources"] = sources
    manifest["macros"] = {
        str(directory): {
            str(p.relative_to(directory)): digest(p)
            for p in sorted(directory.rglob("*"))
            if p.is_file()
        }
        for directory in args.maclib
    }
    for name in names:
        cmd = [
            str(local_as), *include, "-a=" + str(build / f"{name}.lst"),
            "--sym=" + str(build / f"{name}.sym"),
            "-o", str(build / f"{name}.obj"), str(SAMPLE / "src" / f"{name}.asm"),
        ]
        print(" ".join(cmd), flush=True)
        subprocess.run(cmd, check=True, env=env)
    for entry in ["INSSMOK"] + (["INSBAT"] if args.mode == "batch" else []):
        cmd = [
            str(linker), "--norent", "--noreus", "--entry", entry,
            "--name", entry, "-o", str(build / entry), "-iebcopy",
            str(build / f"{entry}.obj"),
            *[str(build / f"{name}.obj") for name in CORE],
        ]
        print(" ".join(cmd), flush=True)
        subprocess.run(cmd, check=True, env=env)
    manifest["outputs"] = {
        p.name: digest(p) for p in sorted(build.iterdir())
        if p.is_file() and p.suffix in [".obj", ".iebcopy"]
    }
    (build / f"{args.mode}-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    )


if __name__ == "__main__":
    main()
