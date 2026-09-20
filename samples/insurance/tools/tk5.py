"""Binary AWS tape transport and strict job capture for the isolated TK5 guest."""

import argparse
from dataclasses import dataclass
import fcntl
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys

from render_jcl import dataset

ROOT = Path(__file__).resolve().parents[1]


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def write_tape(path: Path, files: list[list[bytes]]) -> None:
    previous = 0
    with path.open("xb") as stream:
        for blocks in files:
            for block in blocks:
                if not 0 < len(block) <= 65535:
                    raise ValueError("AWS block length outside supported range")
                stream.write(struct.pack("<HHBB", len(block), previous, 0xA0, 0))
                stream.write(block)
                previous = len(block)
            stream.write(struct.pack("<HHBB", 0, previous, 0x40, 0))
            previous = 0
        stream.write(struct.pack("<HHBB", 0, 0, 0x40, 0))


def read_tape(path: Path, count: int, lrecls: list[int] | None = None) -> list[bytes]:
    raw = path.read_bytes()
    offset, previous = 0, 0
    files: list[bytes] = []
    blocks: list[bytes] = []
    while offset < len(raw):
        if len(raw) - offset < 6:
            raise ValueError("Truncated AWS header")
        length, prev, flag, flags2 = struct.unpack_from("<HHBB", raw, offset)
        offset += 6
        if prev != previous or flags2 or len(raw) - offset < length:
            raise ValueError("Invalid AWS length chain or truncated block")
        if flag == 0x40 and length == 0:
            files.append(b"".join(blocks))
            blocks = []
        elif flag == 0xA0 and length:
            if lrecls and (len(files) >= count or length % lrecls[len(files)]):
                raise ValueError("Tape block contains a partial fixed record")
            blocks.append(raw[offset:offset + length])
        else:
            raise ValueError(f"Unsupported AWS flags/length: {flag:#x}/{length}")
        offset += length
        previous = length
    if blocks or len(files) != count + 1 or files[-1] != b"":
        raise ValueError(f"Expected {count} tape files and terminal tape mark")
    return files[:-1]


@dataclass
class Dataset:
    name: str
    lrecl: int
    blocksize: int
    data: bytes = b""

    def __post_init__(self) -> None:
        dataset(self.name.split("(")[0])
        if "(" in self.name and not re.fullmatch(
            r"[^()]+\([A-Z@$#][A-Z0-9@$#]{0,7}\)", self.name
        ):
            raise ValueError("Invalid PDS member")
        if (self.lrecl <= 0 or not 0 < self.blocksize <= 32760
                or self.blocksize % self.lrecl):
            raise ValueError("FB blocksize must be a multiple of LRECL")
        if len(self.data) % self.lrecl:
            raise ValueError(f"Partial FB{self.lrecl} record")


def header(name: str) -> str:
    return (f"//{name:<8} JOB (ACCT),'INSURANCE',CLASS=A,\n"
            "//             MSGCLASS=A,MSGLEVEL=(1,1)\n")


class Guest:
    def __init__(self, evidence: Path, root: Path, namespace: str = "mvs-smoke"):
        self.evidence = evidence.resolve()
        self.root = root.resolve()
        self.namespace = namespace
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.lock = (self.root / "run.lock").open("a")

    def __enter__(self) -> "Guest":
        fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.lock.close()

    def job(self, label: str, jcl: str, steps: list[str],
            tape: Path | None = None, allow_failure: bool = False) -> dict:
        path = self.evidence / f"{label}.jcl"
        with path.open("x") as stream:
            stream.write(jcl)
        command = [
            sys.executable, str(ROOT / "tools/tk5_job.py"), str(path),
            "--root", str(self.root), "--namespace", self.namespace,
            "--output", str(self.evidence / label), "--steps", ",".join(steps),
            "--timeout", "300",
            "--lock-fd", str(self.lock.fileno()),
        ]
        if tape is not None:
            command += ["--tape", str(tape)]
        result = subprocess.run(command, check=False, capture_output=True, text=True,
                                pass_fds=(self.lock.fileno(),))
        (self.evidence / f"{label}.log").write_text(result.stdout + result.stderr)
        receipt = self.evidence / label / "result.json"
        if not receipt.exists():
            raise RuntimeError(f"{label}: no job receipt; see {self.evidence / (label + '.log')}")
        summary = json.loads(receipt.read_text())
        statuses = " ".join(f"{step}={code}" for step, code in summary["steps"])
        print(f'{summary["job_id"]} {summary["job"]}: {statuses}', flush=True)
        for error in summary["errors"]:
            print("REJECTED:", error, flush=True)
        if result.returncode and not allow_failure:
            raise RuntimeError(f"{label}: job failed; see {self.evidence / label}")
        return summary

    def batch(self, label: str, load: str, prefix: str,
              policies: bytes, transactions: bytes,
              allow_failure: bool = False) -> tuple[bytes, bytes, dict]:
        self.transfer(label + "-in", [
            Dataset(prefix + ".INPOL", 128, 1280, policies),
            Dataset(prefix + ".INTXN", 40, 4000, transactions),
        ], "in")
        jcl = (ROOT / "jcl/run.jcl").read_text()
        for key, value in {"HLQ": load, "RUN": prefix, "ACCOUNT": "ACCT",
                           "CLASS": "A", "MSGCLASS": "A",
                           "POLIN": prefix + ".INPOL",
                           "TXNIN": prefix + ".INTXN"}.items():
            jcl = jcl.replace(f"@{key}@", value)
        outcome = self.job(label + "-run", jcl, ["RUN"], allow_failure=allow_failure)
        policies_out, results_out = self.transfer(label + "-out", [
            Dataset(prefix + ".POL", 128, 1280),
            Dataset(prefix + ".RES", 96, 9600),
        ], "out")
        return policies_out, results_out, outcome

    def transfer(self, label: str, datasets: list[Dataset],
                 direction: str) -> list[bytes]:
        if direction not in ("in", "out"):
            raise ValueError("Unknown transfer direction")
        tape = self.evidence / f"{label}.aws"
        if direction == "in":
            write_tape(tape, [
                [item.data[i:i + item.blocksize]
                 for i in range(0, len(item.data), item.blocksize)]
                for item in datasets
            ])
        else:
            write_tape(tape, [])
        jcl = header("INSTRANS")
        steps = []
        for index, item in enumerate(datasets, 1):
            step = f"C{index:03d}"
            steps.append(step)
            jcl += (f"//{step:<8} EXEC PGM=IEBGENER,REGION=2048K,COND=(0,NE)\n"
                    "//SYSPRINT DD SYSOUT=*\n//SYSIN    DD DUMMY\n")
            tapedd = "SYSUT1" if direction == "in" else "SYSUT2"
            diskdd = "SYSUT2" if direction == "in" else "SYSUT1"
            disposition = "OLD" if direction == "in" else "NEW"
            jcl += (f"//{tapedd:<8} DD DSN=INS.FILE{index},UNIT=480,\n"
                    f"//             VOL=SER=INS001,LABEL=({index},NL),\n"
                    f"//             DISP=({disposition},KEEP),\n"
                    f"//             DCB=(RECFM=FB,LRECL={item.lrecl},\n"
                    f"//             BLKSIZE={item.blocksize})\n")
            if direction == "in" and "(" not in item.name:
                jcl += (f"//{diskdd:<8} DD DSN={item.name},\n"
                        "//             DISP=(NEW,CATLG,DELETE),UNIT=SYSDA,\n"
                        "//             SPACE=(TRK,(100,20)),\n"
                        f"//             DCB=(RECFM=FB,LRECL={item.lrecl},\n"
                        f"//             BLKSIZE={item.blocksize})\n")
            else:
                jcl += (f"//{diskdd:<8} DD DSN={item.name},DISP=OLD\n")
        self.job(label, jcl + "//\n", steps, tape)
        if direction == "in":
            return []
        data = read_tape(tape, len(datasets), [item.lrecl for item in datasets])
        for index, (item, raw) in enumerate(zip(datasets, data), 1):
            if len(raw) % item.lrecl:
                raise ValueError(f"Exported partial record from {item.name}")
            (self.evidence / f"{label}-{index}.bin").write_bytes(raw)
        return data

    def allocate(self, prefix: str) -> None:
        jcl = header("INSALLOC") + "//ALLOC EXEC PGM=IEFBR14\n"
        for suffix, recfm, lrecl, block in (
            ("SRC", "FB", 80, 3200), ("MAC", "FB", 80, 3200),
            ("OBJ", "FB", 80, 3200), ("LOAD", "U", 0, 32760),
        ):
            jcl += (f"//{suffix:<8} DD DSN={prefix}.{suffix},\n"
                    "//             DISP=(NEW,CATLG,DELETE),UNIT=SYSDA,\n"
                    "//             SPACE=(CYL,(10,5,30)),\n"
                    f"//             DCB=(DSORG=PO,RECFM={recfm},\n"
                    f"//             LRECL={lrecl},BLKSIZE={block})\n")
        self.job("allocate", jcl + "//\n", ["ALLOC"])

    def build(self, prefix: str) -> None:
        self.allocate(prefix)
        members = []
        for folder, suffix in (("src", "SRC"), ("copy", "MAC")):
            for path in sorted((ROOT / folder).iterdir()):
                if not path.is_file():
                    continue
                lines = path.read_text().splitlines()
                if any(len(line) > 80 for line in lines):
                    raise ValueError(f"Source exceeds 80 columns: {path}")
                raw = b"".join(line.ljust(80).encode("cp037") for line in lines)
                members.append(Dataset(f"{prefix}.{suffix}({path.stem})",
                                       80, 3200, raw))
        self.transfer("sources", members, "in")
        self.compile(prefix)

    def compile(self, prefix: str) -> None:
        jcl = (ROOT / "jcl/build.jcl").read_text()
        for key, value in {"HLQ": prefix, "ACCOUNT": "ACCT", "CLASS": "A",
                           "MSGCLASS": "A",
                           "SYSLIB": "//         DD DSN=SYS1.MACLIB,DISP=SHR"}.items():
            jcl = jcl.replace(f"@{key}@", value)
        self.job("build", jcl, ["ACALC", "AVAL", "APACK", "ADATE", "ARATE",
                                "ASMOK", "ABAT", "LSMOK", "LBAT", "SMOKE"])

    def host(self, prefix: str) -> None:
        self.allocate(prefix)
        members = [
            Dataset(f"{prefix}.OBJ({path.stem})", 80, 3200, path.read_bytes())
            for path in sorted((ROOT / "build").glob("*.obj"))
        ]
        if len(members) != 7:
            raise ValueError("Expected seven host object decks")
        self.transfer("objects", members, "in")
        jcl = (ROOT / "jcl/link.jcl").read_text()
        for key, value in {"HLQ": prefix, "ACCOUNT": "ACCT", "CLASS": "A",
                           "MSGCLASS": "A"}.items():
            jcl = jcl.replace(f"@{key}@", value)
        self.job("link", jcl, ["LSMOK", "LBAT", "SMOKE"])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prove", "build", "compile", "host"])
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=Path.home() / "mvs-demo")
    parser.add_argument("--prefix", type=dataset, required=True)
    args = parser.parse_args()
    with Guest(args.evidence, args.root) as guest:
        if args.command == "build":
            guest.build(args.prefix)
        elif args.command == "compile":
            guest.compile(args.prefix)
        elif args.command == "host":
            guest.host(args.prefix)
        else:
            prove(guest, args.prefix)


def prove(guest: Guest, prefix: str) -> None:
    fixtures = [
        Dataset(f"{prefix}.F{size}", size, size * 10,
                bytes(range(size)) + b"\0" * size + b"\x40" * size)
        for size in (128, 40, 96)
    ]
    guest.transfer("load", fixtures, "in")
    observed = guest.transfer("export", fixtures, "out")
    results = []
    for fixture, raw in zip(fixtures, observed):
        if raw != fixture.data:
            raise ValueError(f"Binary round trip differs: FB{fixture.lrecl}")
        results.append({"lrecl": fixture.lrecl, "bytes": len(raw),
                        "sha256": digest(raw), "identical": True})
    (guest.evidence / "roundtrip.json").write_text(json.dumps(results, indent=2) + "\n")
    print("PASS: FB128, FB40 and FB96 preserved exactly, including blank records")


if __name__ == "__main__":
    main()
