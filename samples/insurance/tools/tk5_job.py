#!/usr/bin/env python3
"""Submit real binary cards to the isolated TK5 guest and retain raw evidence."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import time


def control(namespace: str, command: str) -> str:
    result = subprocess.run(
        ["sudo", "-n", "ip", "netns", "exec", namespace, "curl",
         "--fail", "--silent", "--show-error", "--max-time", "10", "-G",
         "--data-urlencode", f"cmd={command}",
         "http://127.0.0.1:8038/cgi-bin/tasks/cmd"],
        check=True, capture_output=True, text=True,
    )
    if re.search(r"HHC\d+E", result.stdout):
        raise RuntimeError(result.stdout)
    return result.stdout


def snapshot(path: Path, offset: int) -> bytes:
    if not path.exists():
        return b""
    with path.open("rb") as stream:
        stream.seek(offset)
        return stream.read()


def execute(namespace: str, commands: list[str], output: Path,
            watched: list[Path], offsets: dict[Path, int], jobname: str,
            tape: Path | None, steps: int, timeout: int) -> bool:
    command_log = output / "commands.txt"

    def send(command: str) -> None:
        with command_log.open("a") as stream:
            stream.write(f"\n> {command}\n{control(namespace, command)}")

    try:
        for command in commands:
            send(command)
        deadline = time.monotonic() + timeout
        mounts = 0
        while time.monotonic() < deadline:
            console = snapshot(watched[0], offsets[watched[0]]).decode(
                "utf-8", errors="replace")
            requests = re.findall(rf"IEF233A M 480,INS001,,{jobname},\S+", console)
            if tape and len(requests) > mounts:
                if len(requests) > steps + 1:
                    raise RuntimeError("Repeated unexpected tape mount requests")
                send(f"devinit 0480 {tape.resolve()}")
                mounts = len(requests)
            if re.search(rf"\$HASP250\s+{jobname}\s+IS PURGED", console):
                return True
            time.sleep(0.5)
        return False
    finally:
        for path in watched:
            (output / path.name).write_bytes(snapshot(path, offsets[path]))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jcl", type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--binary-input", type=Path,
                        help="Replace @@BINARY@@ with raw 80-byte records")
    parser.add_argument("--root", type=Path,
                        default=Path.home() / "mvs-demo")
    parser.add_argument("--namespace", default="mvs-smoke")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--steps", required=True,
                        help="Comma-separated required step names, all RC=0")
    parser.add_argument("--expect", action="append", default=[])
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--tape", type=Path,
                        help="AWS tape to mount on the existing 0480 device")
    parser.add_argument("--lock-fd", type=int,
                        help="Inherited generation-controller lock descriptor")
    args = parser.parse_args()
    args.root = args.root.resolve()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    lock = (os.fdopen(os.dup(args.lock_fd), "a") if args.lock_fd is not None
            else (args.root / "run.lock").open("a"))
    with lock:
        if os.fstat(lock.fileno()).st_ino != (args.root / "run.lock").stat().st_ino:
            raise ValueError("Inherited lock is not the guest writer lock")
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        quarantine = args.root / "quarantine.json"
        if quarantine.exists():
            raise RuntimeError(f"Uncertain guest job state; inspect {quarantine} before reuse")
        jcl = args.jcl.read_text()
        if args.source:
            jcl = jcl.replace("@@SOURCE@@", args.source.read_text().rstrip())
        lines = jcl.splitlines()
        user, password = os.getenv("TK5_JOB_USER"), os.getenv("TK5_JOB_PASSWORD")
        if bool(user) != bool(password):
            raise ValueError("Both TK5_JOB_USER and TK5_JOB_PASSWORD are required")
        if user and password:
            if not re.fullmatch(r"[A-Z0-9@$#]{1,8}", user):
                raise ValueError("Invalid TK5 user name")
            if not re.fullmatch(r"[A-Z0-9@$#]{1,8}", password):
                raise ValueError("Unsupported TK5 password characters")
            continued = lines[0].endswith(",")
            if not continued:
                lines[0] += ","
            auth = f"//         USER={user},PASSWORD={password}"
            lines.insert(1, auth + ("," if continued else ""))
        if any(len(line) > 80 for line in lines):
            raise ValueError("JCL/source record exceeds 80 columns")
        job = re.match(r"//([A-Z0-9@$#]{1,8})\s+JOB\b", lines[0])
        if not job:
            raise ValueError("First card must be a named JOB statement")
        jobname = job.group(1)
        card_parts = []
        for line in lines:
            if line == "@@BINARY@@" and args.binary_input:
                data = args.binary_input.read_bytes()
                if not data or len(data) % 80:
                    raise ValueError("Binary input must contain full 80-byte records")
                if any(data[i:i + 2] == "ZZ".encode("cp037")
                       for i in range(0, len(data), 80)):
                    raise ValueError("Binary record collides with ZZ delimiter")
                card_parts.append(data)
            elif line.startswith("@@"):
                raise ValueError("Unexpanded JCL input marker")
            else:
                card_parts.append(line.ljust(80).encode("cp037"))
        cards = b"".join(card_parts)
        cardfile = args.output / "submitted.ebcdic"
        cardfile.write_bytes(cards)
        (args.output / "submitted.jcl").write_text(jcl)
        guest = args.root / "mvs-tk5"
        watched = [args.root / "evidence/console.log",
                   *sorted((guest / "prt").glob("*.txt"))]
        offsets = {path: path.stat().st_size for path in watched}
        commands = [
            f"devinit 00d {args.output / 'ifox.punch'} ebcdic",
            "/$SPRT1", "/$SPRT2", "/$SPRT3", "/$SPUN1", "/$SRDR1",
            f"devinit 00c {cardfile} ebcdic eof",
        ]
        if args.tape:
            commands.insert(0, f"devinit 0480 {args.tape.resolve()}")
        quarantine.write_text(json.dumps({
            "job": jobname, "evidence": str(args.output),
            "reason": "Submission in progress; remove only after confirming JES purge or cancelling and purging",
        }, indent=2) + "\n")
        print(f"Submitting {jobname}: {len(cards) // 80} real CP037 cards",
              flush=True)
        complete = execute(args.namespace, commands, args.output, watched, offsets,
                           jobname, args.tape, len(args.steps.split(",")), args.timeout)
        console = (args.output / watched[0].name).read_text(errors="replace")
        spool = "\n".join(
            (args.output / path.name).read_text(errors="replace")
            for path in watched[1:])
        completions = re.findall(
            rf"IEF142I\s+{jobname}\s+(\S+)(?:\s+(\S+))?"
            r"\s+- STEP WAS EXECUTED - COND CODE\s+(\d+)", spool)
        statuses = [(outer or inner, code) for inner, outer, code in completions]
        errors = []
        if not complete:
            errors.append("Timed out waiting for JES purge/output completion")
        for step in args.steps.split(","):
            values = [int(code) for name, code in statuses if name == step]
            if values != [0]:
                errors.append(f"{step}: expected one RC=0, found {values}")
        for step, code in statuses:
            if int(code):
                errors.append(f"{step}: nonzero RC={code}")
        if re.search(r"IEF450I|IEF472I|IEF272I|JCL ERROR", spool):
            errors.append("ABEND, skipped step, or JCL error in spool")
        for text in args.expect:
            if not re.search(rf"^\s*{re.escape(text)}\s*$", spool, re.MULTILINE):
                errors.append(f"Required output missing: {text}")
        job_ids = re.findall(rf"\bJOB\s*(\d+)\s+\$HASP373\s+{jobname}\b", console)
        job_ids = [f"JOB{int(number):05d}" for number in job_ids]
        if len(set(job_ids)) != 1:
            errors.append(f"Expected one JES job ID, found {job_ids}")
        summary = {"job": jobname, "job_id": job_ids[0] if job_ids else None,
                   "steps": statuses,
                   "purged": complete, "errors": errors,
                   "passed": not errors}
        (args.output / "result.json").write_text(
            json.dumps(summary, indent=2) + "\n")
        if complete:
            quarantine.unlink()
        print(json.dumps(summary, indent=2), flush=True)
        if errors:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
