"""Import ld370 IEBCOPY output through real VS tape records and execute it."""

import argparse
from pathlib import Path
import struct

from tk5 import Guest, ROOT, header, write_tape


def unload_blocks(raw: bytes) -> list[bytes]:
    if len(raw) < 328 or raw[:4] != bytes.fromhex("00ca6d0f"):
        raise ValueError("Expected ld370 IEBCOPY COPYR1/COPYR2 headers")
    blocksize = int.from_bytes(raw[14:16], "big")
    logical = [raw[:52], raw[52:328]]
    offset = 328
    while offset < len(raw):
        if len(raw) - offset < 12:
            raise ValueError("Truncated IEBCOPY count header")
        length = 12 + raw[offset + 9] + int.from_bytes(raw[offset + 10:offset + 12], "big")
        record = raw[offset:offset + length]
        if len(record) != length:
            raise ValueError("Truncated IEBCOPY record")
        offset += length
        if raw[offset:offset + 12] == bytes(12):
            record += bytes(12)
            offset += 12
        logical.append(record)
    if b"".join(logical) != raw:
        raise ValueError("Unframing changed the IEBCOPY artifact")
    if any(len(record) + 8 > blocksize for record in logical):
        raise ValueError("Unload record exceeds declared VS block size")
    return [struct.pack(">HHHH", len(record) + 8, 0, len(record) + 4, 0) + record
            for record in logical]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--root", type=Path, default=Path("/home/ubuntu/mvs-demo"))
    args = parser.parse_args()
    with Guest(args.evidence, args.root) as guest:
        guest.allocate(args.prefix)
        entries = ("INSSMOK", "INSBAT")
        artifacts = [(ROOT / "build" / (entry + ".iebcopy")).read_bytes() for entry in entries]
        tape = guest.evidence / "ld370.aws"
        write_tape(tape, [unload_blocks(raw) for raw in artifacts])
        jcl = header("INSLD")
        for index, (entry, raw) in enumerate(zip(entries, artifacts), 1):
            (guest.evidence / (entry + ".iebcopy")).write_bytes(raw)
            blocksize = int.from_bytes(raw[14:16], "big")
            jcl += (
                f"//C{index:03d} EXEC PGM=IEBCOPY,REGION=2048K,COND=(0,NE)\n"
                "//SYSPRINT DD SYSOUT=*\n"
                "//SYSUT1 DD DSN=INS.UNLOAD,UNIT=480,\n"
                f"//         VOL=SER=INS001,LABEL=({index},NL),DISP=(OLD,KEEP),\n"
                f"//         DCB=(RECFM=VS,LRECL={blocksize - 4},BLKSIZE={blocksize})\n"
                f"//SYSUT2 DD DSN={args.prefix}.LOAD,DISP=OLD\n"
                "//SYSUT3 DD UNIT=SYSDA,SPACE=(TRK,(5,5))\n"
                "//SYSUT4 DD UNIT=SYSDA,SPACE=(TRK,(5,5))\n"
                "//SYSIN DD *\n"
                " COPY INDD=SYSUT1,OUTDD=SYSUT2\n/*\n"
            )
        jcl += (
            "//SMOKE EXEC PGM=INSSMOK,REGION=512K,COND=(0,NE)\n"
            f"//STEPLIB DD DSN={args.prefix}.LOAD,DISP=SHR\n"
            "//SYSUDUMP DD SYSOUT=*\n//\n"
        )
        guest.job("import", jcl, ["C001", "C002", "SMOKE"], tape)


if __name__ == "__main__":
    main()
