"""Independent per-routine expectation model.

Arithmetic and calendar facts come from Python integers and datetime.date,
never from the assembler or its listings. Rule ORDER (which rejection wins)
is taken from the frozen business contract (docs/formulas, BR-002..BR-010) and
is labelled "source-derived" in contract.json. Field-level hand calculations
for the anchor cases live in tests/test_model.py as literal numbers.
"""

from dataclasses import dataclass
from datetime import date

from layout import (FIELDS, MAX_AMOUNT, WORKLEN, binary, number, packed,
                    packed_valid, text, unpacked)

RATES = [(19000101, 125, 700), (20000101, 175, 600), (20200101, 225, 500),
         (20240101, 300, 400), (20250101, 325, 350)]
ZERO3, ZERO7 = packed(0, 3), packed(0, 7)


@dataclass(frozen=True)
class Outcome:
    r15: int
    work: bytes
    arg: bytes
    notes: tuple[str, ...] = ()


class Work:
    def __init__(self, raw: bytes):
        if len(raw) != WORKLEN:
            raise ValueError("work image length")
        self.raw = bytearray(raw)

    def get(self, name: str) -> bytes:
        f = FIELDS[name]
        return bytes(self.raw[f.offset:f.end])

    def set(self, name: str, value: bytes) -> None:
        f = FIELDS[name]
        if len(value) != f.size:
            raise ValueError(f"{name}: {len(value)} bytes for {f.size}")
        self.raw[f.offset:f.end] = value

    def int(self, name: str) -> int:
        return number(self.get(name))

    def num(self, name: str) -> int:
        return unpacked(self.get(name))


def calendar(value: int) -> date | None:
    """Gregorian date for yyyymmdd within 1900..2099, else None (BR-002)."""
    if value <= 0:
        return None
    year, md = divmod(value, 10000)
    month, day = divmod(md, 100)
    if not 1900 <= year <= 2099 or not 1 <= month <= 12 or day < 1:
        return None
    try:
        return date(year, month, day)
    except ValueError:
        return None


def ordinal(value: date) -> int:
    return (value - date(1900, 1, 1)).days


def inspack(work: Work, arg: bytes) -> Outcome:
    """Seven packed bytes at R1: digits 0-9, final sign C, D or F."""
    return Outcome(0 if packed_valid(arg[:7]) else 8, bytes(work.raw), arg)


def insdate(work: Work) -> None:
    """In-place INSDATE effect: WVALID always, WYEAR/WMD/WORD conditionally."""
    work.set("WVALID", binary(8))
    value = work.int("WDATE")
    if value <= 0:
        return
    year, md = divmod(value, 10000)
    month, day = divmod(md, 100)
    work.set("WYEAR", binary(year))
    when = calendar(value)
    if when is None:
        return
    work.set("WMD", binary(month * 100 + day))
    work.set("WORD", binary(ordinal(when)))
    work.set("WVALID", binary(0))


def insdate_routine(work: Work, arg: bytes) -> Outcome:
    insdate(work)
    return Outcome(0, bytes(work.raw), arg)


def insrate(work: Work) -> tuple[str, ...]:
    notes: list[str] = []
    when = work.int("TDATE")
    matched = False
    for boundary, rate, fee in RATES:
        if when < boundary:
            break
        work.set("WRATE", packed(rate, 3))
        work.set("WFEE", packed(fee, 3))
        matched = True
    if not matched:
        notes.append("TDATE below 19000101: WRATE/WFEE not written (outside domain)")
    if work.int("WAGE") >= 10:
        work.set("WFEE", ZERO3)
    return tuple(notes)


def insrate_routine(work: Work, arg: bytes) -> Outcome:
    notes = insrate(work)
    return Outcome(0, bytes(work.raw), arg, notes)


def insval(work: Work) -> int:
    """BR-004 state validation; returns R15 and leaves WVALID 0 or 8."""
    r15 = _insval(work)
    work.set("WVALID", binary(r15))
    return r15


def _insval(work: Work) -> int:
    work.set("WVALID", binary(8))
    sid = work.get("SID")
    if any(not 0xF0 <= b <= 0xF9 for b in sid):
        return 8
    if work.get("SPAD") != bytes(7) or work.get("STAIL") != bytes(40):
        return 8
    if work.int("SSEQ") < 0:
        return 8
    for name in ("SFACE", "SCASH", "SLOAN"):
        raw = work.get(name)
        if not packed_valid(raw):
            return 8
        if not 0 <= unpacked(raw) <= MAX_AMOUNT:
            return 8
    if work.num("SLOAN") > work.num("SCASH"):
        return 8
    for name in ("SISSUE", "SDATE"):
        work.set("WDATE", work.get(name))
        insdate(work)
        if work.int("WVALID") != 0:
            return 8
    if work.int("SDATE") < work.int("SISSUE"):
        return 8
    if work.int("SSEQ") == 0:
        if work.get("SLAST") != bytes(40) or work.get("SDATE") != work.get("SISSUE"):
            return 8
    else:
        last = work.get("SLAST")
        if (last[:8] != sid or last[8:12] != work.get("SSEQ")
                or last[12:16] != work.get("SDATE")):
            return 8
    return 0


def insval_routine(work: Work, arg: bytes) -> Outcome:
    return Outcome(insval(work), bytes(work.raw), arg)


def half_up(numerator: int, denominator: int) -> int:
    return (numerator + denominator // 2) // denominator


def inscalc(work: Work, arg: bytes) -> Outcome:
    before = work.get("SREC")
    work.set("WBEFORE", before)
    work.set("OREC", bytes(96))
    work.set("OID", work.get("TID"))
    work.set("OSEQ", work.get("TSEQ"))
    work.set("ODATE", work.get("TDATE"))
    work.set("OOP", work.get("TOP"))
    work.set("OVERS", text("V001"))
    for name in ("ORATE", "OFEE"):
        work.set(name, ZERO3)
    for name in ("OCASH", "OSURR", "ODEATH", "OLOAN", "OINT", "OCHG"):
        work.set(name, ZERO7)
    notes: list[str] = []

    def exit_(status: str) -> Outcome:
        work.set("OSTAT", text(status))
        return Outcome(0, bytes(work.raw), arg, tuple(notes))

    def fail(status: str) -> Outcome:
        work.set("SREC", before)
        work.set("OINT", ZERO7)
        return exit_(status)

    if insval(work):
        return exit_("STAT")
    cash, loan = work.num("SCASH"), work.num("SLOAN")
    work.set("OCASH", packed(cash))
    work.set("OLOAN", packed(loan))
    if work.get("SID") != work.get("TID"):
        return exit_("NPOL")
    seq, old = work.int("TSEQ"), work.int("SSEQ")
    if seq <= 0 or seq < old:
        return exit_("ORDR")
    if seq == old:
        return exit_("DUPL" if work.get("TREC") == work.get("SLAST") else "CNFL")
    if work.get("TPAD") != bytes(3) or work.get("TTAIL") != bytes(13):
        return exit_("FORM")
    if not packed_valid(work.get("TAMT")):
        return exit_("PACK")
    amount = work.num("TAMT")
    if amount < 0:
        return exit_("NEGA")
    if amount > MAX_AMOUNT:
        return exit_("OVER")
    work.set("WDATE", work.get("TDATE"))
    insdate(work)
    if work.int("WVALID") != 0:
        return exit_("DATE")
    work.set("WOLDORD", work.get("WORD"))
    work.set("WISSYR", work.get("WYEAR"))
    work.set("WISSMD", work.get("WMD"))
    if work.int("TDATE") < work.int("SDATE"):
        return exit_("DATE")
    work.set("WDATE", work.get("SISSUE"))
    insdate(work)
    age = work.int("WISSYR") - work.int("WYEAR")
    if work.int("WISSMD") < work.int("WMD"):
        age -= 1
    work.set("WAGE", binary(age))
    work.set("WDATE", work.get("SDATE"))
    insdate(work)
    days = work.int("WOLDORD") - work.int("WORD")
    work.set("WDAYS", binary(days))
    notes.extend(insrate(work))
    rate, fee = work.num("WRATE"), work.num("WFEE")
    interest = half_up(cash * rate * days, 3_650_000)
    if interest > MAX_AMOUNT:
        return fail("OVER")
    work.set("OINT", packed(interest))
    cash += interest
    work.set("SCASH", packed(cash))
    if cash > MAX_AMOUNT:
        return fail("OVER")
    op = work.get("TOP").decode("cp037")
    if op == "P":
        cash += amount
    elif op == "W":
        cash -= amount
    elif op == "L":
        loan += amount
    elif op == "R":
        loan -= amount
    elif op in ("Q", "D"):
        if amount != 0:
            return fail("AMNT")
    else:
        return fail("TYPE")
    work.set("SCASH", packed(cash))
    if op in ("L", "R"):
        work.set("SLOAN", packed(loan))
    if cash < 0 or loan < 0 or loan > cash:
        return fail("FUND")
    if cash > MAX_AMOUNT or loan > MAX_AMOUNT:
        return fail("OVER")
    charge = half_up(cash * fee, 10_000)
    surrender = max(0, cash - charge - loan)
    death = max(0, max(work.num("SFACE"), cash) - loan)
    work.set("OCHG", packed(charge))
    work.set("OSURR", packed(surrender))
    work.set("ODEATH", packed(death))
    work.set("SSEQ", work.get("TSEQ"))
    work.set("SDATE", work.get("TDATE"))
    work.set("SLAST", work.get("TREC"))
    work.set("OAGE", work.get("WAGE"))
    work.set("ORATE", packed(rate, 3))
    work.set("OFEE", packed(fee, 3))
    work.set("OCASH", packed(cash))
    work.set("OLOAN", packed(loan))
    return exit_("OKAY")


MODELS = {"INSPACK": inspack, "INSDATE": insdate_routine, "INSRATE": insrate_routine,
          "INSVAL": insval_routine, "INSCALC": inscalc}


def expect(routine: str, work: bytes, arg: bytes) -> Outcome:
    return MODELS[routine](Work(work), arg)
