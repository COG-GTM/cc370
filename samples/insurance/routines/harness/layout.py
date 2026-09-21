"""Byte layouts shared by the driver, the model, the comparator and the docs.

Offsets are stated here once and cross-checked against the as370 symbol tables
of the frozen modules and of the driver by the host tests. Nothing here reads
the assembler source to derive an expectation.
"""

from dataclasses import dataclass
import hashlib
from pathlib import Path

ROUTINES = Path(__file__).resolve().parents[1]
INSURANCE = ROUTINES.parent

WORKLEN = 460
CASE_LRECL = 512
CAPTURE_LRECL = 1536
MAX_AMOUNT = 99_999_999_999
LIBRARY = ("INSPACK", "INSDATE", "INSRATE", "INSVAL", "INSCALC")
PROGRAMS = ("INSBAT", "INSSMOK")
SENTINEL = {n: 0x0BAD0000 + n for n in (0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 14)}
GUARD = {
    "work_low": bytes([0xA5]) * 16, "work_high": bytes([0x5A]) * 16,
    "arg_low": bytes([0xC3]) * 8, "arg_high": bytes([0x3C]) * 8,
    "save_low": bytes([0x96]) * 8, "save_high": bytes([0x69]) * 8,
}
FLAG_KEEP, FLAG_STATE, FLAG_TXN = 0x80, 0x40, 0x20


@dataclass(frozen=True)
class Field:
    name: str
    offset: int
    size: int
    kind: str  # text | binary | packed | hex
    group: str  # state | txn | result | scratch

    @property
    def end(self) -> int:
        return self.offset + self.size


WORK = [
    Field("SID", 0, 8, "text", "state"), Field("SISSUE", 8, 4, "binary", "state"),
    Field("SDATE", 12, 4, "binary", "state"), Field("SSEQ", 16, 4, "binary", "state"),
    Field("SFACE", 20, 7, "packed", "state"), Field("SCASH", 27, 7, "packed", "state"),
    Field("SLOAN", 34, 7, "packed", "state"), Field("SPAD", 41, 7, "hex", "state"),
    Field("SLAST", 48, 40, "hex", "state"), Field("STAIL", 88, 40, "hex", "state"),
    Field("TID", 128, 8, "text", "txn"), Field("TSEQ", 136, 4, "binary", "txn"),
    Field("TDATE", 140, 4, "binary", "txn"), Field("TOP", 144, 1, "text", "txn"),
    Field("TPAD", 145, 3, "hex", "txn"), Field("TAMT", 148, 7, "packed", "txn"),
    Field("TTAIL", 155, 13, "hex", "txn"),
    Field("OID", 168, 8, "text", "result"), Field("OSEQ", 176, 4, "binary", "result"),
    Field("ODATE", 180, 4, "binary", "result"), Field("OSTAT", 184, 4, "text", "result"),
    Field("OOP", 188, 1, "text", "result"), Field("OPAD", 189, 3, "hex", "result"),
    Field("OAGE", 192, 4, "binary", "result"), Field("ORATE", 196, 3, "packed", "result"),
    Field("OFEE", 199, 3, "packed", "result"), Field("OCASH", 202, 7, "packed", "result"),
    Field("OSURR", 209, 7, "packed", "result"), Field("ODEATH", 216, 7, "packed", "result"),
    Field("OLOAN", 223, 7, "packed", "result"), Field("OINT", 230, 7, "packed", "result"),
    Field("OCHG", 237, 7, "packed", "result"), Field("OVERS", 244, 4, "text", "result"),
    Field("OTAIL", 248, 16, "hex", "result"),
    Field("WDATE", 264, 4, "binary", "scratch"), Field("WYEAR", 268, 4, "binary", "scratch"),
    Field("WMD", 272, 4, "binary", "scratch"), Field("WORD", 276, 4, "binary", "scratch"),
    Field("WVALID", 280, 4, "binary", "scratch"), Field("WOLDORD", 284, 4, "binary", "scratch"),
    Field("WISSYR", 288, 4, "binary", "scratch"), Field("WISSMD", 292, 4, "binary", "scratch"),
    Field("WAGE", 296, 4, "binary", "scratch"), Field("WDAYS", 300, 4, "binary", "scratch"),
    Field("WRATE", 304, 3, "packed", "scratch"), Field("WFEE", 307, 3, "packed", "scratch"),
    Field("WPAD", 310, 2, "hex", "scratch"), Field("WNUM", 312, 8, "hex", "scratch"),
    Field("WPROD", 320, 12, "hex", "scratch"), Field("WBEFORE", 332, 128, "hex", "scratch"),
]
RECORDS = [Field("SREC", 0, 128, "hex", "state"), Field("TREC", 128, 40, "hex", "txn"),
           Field("OREC", 168, 96, "hex", "result")]
FIELDS = {field.name: field for field in WORK + RECORDS}

# Capture record produced by INSDRV for every case, in physical case order.
CAPTURE = {
    "id": (0, 8), "routine": (8, 8), "ordinal": (16, 4), "flags": (20, 1),
    "regs_before": (24, 64), "regs_after": (88, 64),
    "caller_save": (152, 72), "callee_save": (224, 72),
    "guard_work_low": (296, 16), "guard_work_high": (312, 16),
    "arg_after": (328, 8), "guard_arg_low": (336, 8), "guard_arg_high": (344, 8),
    "guard_save_low": (352, 8), "guard_save_high": (360, 8),
    "addr_work": (368, 4), "addr_arg": (372, 4), "addr_save": (376, 4),
    "addr_return": (380, 4), "addr_base": (384, 4),
    "work_before": (400, WORKLEN), "work_after": (860, WORKLEN),
}
CASE = {"id": (0, 8), "routine": (8, 8), "ordinal": (16, 4), "flags": (20, 1),
        "arg": (24, 8), "work": (32, WORKLEN)}


def text(value: str, size: int | None = None) -> bytes:
    raw = value.encode("cp037")
    return raw if size is None else raw.ljust(size, b"\x40")


def binary(value: int) -> bytes:
    return value.to_bytes(4, "big", signed=True)


def number(raw: bytes) -> int:
    return int.from_bytes(raw, "big", signed=True)


def packed(value: int, size: int = 7, sign: str | None = None) -> bytes:
    """Encode a signed packed decimal; C/D canonical, F only on request."""
    digits = str(abs(value)).zfill(size * 2 - 1)
    if len(digits) > size * 2 - 1:
        raise ValueError(f"{value} does not fit PL{size}")
    final = sign or ("D" if value < 0 else "C")
    if value < 0 and final != "D":
        raise ValueError("negative values carry sign D")
    return bytes.fromhex(digits + final)


def packed_valid(raw: bytes) -> bool:
    text_ = raw.hex()
    return text_[:-1].isdigit() and text_[-1] in "cdf"


def unpacked(raw: bytes) -> int:
    """Numeric value of a packed field accepted by INSPACK (signs C, D, F)."""
    if not packed_valid(raw):
        raise ValueError(f"malformed packed decimal {raw.hex()}")
    magnitude = int(raw.hex()[:-1])
    return -magnitude if raw.hex()[-1] == "d" else magnitude


def decode_field(field: Field, raw: bytes) -> str:
    if field.kind == "binary":
        return str(number(raw))
    if field.kind == "text":
        return repr(raw.decode("cp037"))
    if field.kind == "packed":
        return str(unpacked(raw)) if packed_valid(raw) else f"malformed:{raw.hex()}"
    return raw.hex()


def decode_work(raw: bytes) -> dict[str, str]:
    return {f.name: decode_field(f, raw[f.offset:f.end]) for f in WORK}


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def symbols(path: Path) -> dict[str, tuple[int, int]]:
    """Return {symbol: (value, length)} from an as370 --sym table."""
    table = {}
    for line in path.read_text().splitlines():
        if line.startswith("#") or not line.strip():
            continue
        parts = line.split("\t")
        table.setdefault(parts[0], (int(parts[1]), int(parts[2])))
    return table
