"""Negative and immutable-rejection controls executed by the real guest."""

import argparse
from pathlib import Path
import subprocess

from codec import decode, records, state, transaction
from tk5 import Dataset, Guest, ROOT, header
from tk5_validate import save, sha


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--load-prefix", required=True)
    parser.add_argument("--root", type=Path, default=Path("/home/ubuntu/mvs-demo"))
    args = parser.parse_args()
    with Guest(args.evidence, args.root) as guest:
        results = []
        lock = subprocess.run(["flock", "--nonblock", str(args.root / "run.lock"), "true"],
                              check=False)
        if lock.returncode != 1:
            raise ValueError("A second writer acquired the generation lock")
        results.append({"control": "single_writer", "competing_process_rc": lock.returncode})
        initial = (ROOT / "golden/v1/anchors.polin.bin").read_bytes()[:128]
        bad = bytearray(initial)
        bad[20] = 0xFF
        masters = {
            "duplicate": initial + initial,
            "packed": bytes(bad),
            "oversize": b"".join(state(f"{i:08d}", 20240101, 1000000, 100000)
                                  for i in range(1, 514)),
        }
        for index, (name, master) in enumerate(masters.items(), 1):
            prefix = f"{args.prefix}.N{index:02d}"
            pol, res, job = guest.batch(name, args.load_prefix, prefix, master, b"",
                                        allow_failure=True)
            if job["passed"] or job["steps"] != [["RUN", "0012"]] or pol or res:
                raise ValueError(f"{name}: expected rejected RC12 and empty outputs")
            returned = guest.transfer(name + "-input-check",
                                      [Dataset(prefix + ".INPOL", 128, 1280)], "out")[0]
            if returned != master:
                raise ValueError("Invalid-master rejection altered the input dataset")
            results.append({"control": name, "job": job, "output_bytes": [len(pol), len(res)],
                            "input_unchanged_sha256": sha(returned)})
        job = guest.job("abend", header("INSABEND") +
                        "//RUN EXEC PGM=NOINSBAD\n//SYSUDUMP DD SYSOUT=*\n//\n",
                        ["RUN"], allow_failure=True)
        spool = (guest.evidence / "abend/prt00e.txt").read_text(errors="replace")
        if job["passed"] or "S806" not in spool:
            raise ValueError("Expected missing-program S806 and fail-closed runner")
        results.append({"control": "abend", "job": job, "observed_abend": "S806"})
        first = transaction("00000001", 10, 20250101, "P", 10000)
        committed, _, commit_job = guest.batch("commit", args.load_prefix,
                                              args.prefix + ".GOOD", initial, first)
        malformed = bytearray(transaction("00000001", 11, 20250101, "P", 1))
        malformed[20] = 0xFA
        requests = (first + transaction("00000001", 10, 20250101, "P", 10001)
                    + transaction("00000001", 9, 20250101, "Q", 0)
                    + bytes(malformed) + transaction("00000001", 11, 20250230, "Q", 0))
        unchanged, raw, reject_job = guest.batch("reject", args.load_prefix,
                                                args.prefix + ".REJECT", committed, requests)
        actual = [decode(record, "result") for record in records(raw, "result")]
        if unchanged != committed:
            raise ValueError("Rejected requests changed committed policy bytes")
        statuses = [record["status"] for record in actual]
        if statuses != ["DUPL", "CNFL", "ORDR", "PACK", "DATE"]:
            raise ValueError(f"Unexpected rejection statuses: {statuses}")
        results.append({"control": "immutable_rejections", "commit_job": commit_job,
                        "rejection_job": reject_job, "statuses": statuses,
                        "unchanged_master_sha256": sha(unchanged), "decoded_results": actual})
        save(guest.evidence / "controls.json", results)
        print("PASS: RC12, S806, duplicate/conflict/order/malformed immutability and writer lock")


if __name__ == "__main__":
    main()
