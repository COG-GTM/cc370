"""Case inventory: independently chosen inputs for every callable routine.

Each case names the contract rule it measures. Expected outputs come from
model.py; the physical order of this list is the required capture order.
"""

from dataclasses import dataclass, field

from layout import (FIELDS, FLAG_KEEP, FLAG_STATE, FLAG_TXN, MAX_AMOUNT, WORKLEN,
                    binary, packed, text)

SENT4 = bytes.fromhex("7F7F7F7F")  # pre-fill for conditionally written scratch
SENT3 = bytes.fromhex("7F7F7F")
P0, P0D, P0F = packed(0), packed(0, sign="D"), packed(0, sign="F")


@dataclass
class Case:
    id: str
    routine: str
    rule: str
    note: str
    work: bytes = bytes(WORKLEN)
    arg: bytes = bytes(8)
    flags: int = 0
    tags: tuple[str, ...] = ()
    hand: dict[str, int | str] = field(default_factory=dict)  # hand-worked expectations

    def __post_init__(self) -> None:
        if not 1 <= len(self.id) <= 8 or not self.id.isascii() or not self.id.isupper():
            raise ValueError(f"case id {self.id!r}")
        if len(self.work) != WORKLEN or len(self.arg) != 8:
            raise ValueError(f"{self.id}: fixture size")


def work(**values: bytes) -> bytes:
    raw = bytearray(WORKLEN)
    for name, value in values.items():
        f = FIELDS[name]
        if len(value) != f.size:
            raise ValueError(f"{name}: {len(value)} bytes for {f.size}")
        raw[f.offset:f.end] = value
    return bytes(raw)


def state(sid: str = "00000001", issue: int = 20200101, date: int | None = None,
          seq: int = 0, face: bytes = packed(10_000_000), cash: bytes = packed(500_000),
          loan: bytes = P0, last: bytes = bytes(40), pad: bytes = bytes(7),
          tail: bytes = bytes(40), raw_id: bytes | None = None) -> dict[str, bytes]:
    return {"SID": raw_id or text(sid), "SISSUE": binary(issue),
            "SDATE": binary(issue if date is None else date), "SSEQ": binary(seq),
            "SFACE": face, "SCASH": cash, "SLOAN": loan, "SPAD": pad, "SLAST": last,
            "STAIL": tail}


def txn(tid: str = "00000001", seq: int = 1, date: int = 20210101, op: str = "P",
        amount: bytes = packed(100_000), pad: bytes = bytes(3),
        tail: bytes = bytes(13)) -> dict[str, bytes]:
    return {"TID": text(tid), "TSEQ": binary(seq), "TDATE": binary(date),
            "TOP": text(op), "TPAD": pad, "TAMT": amount, "TTAIL": tail}


def trec(**values: bytes) -> bytes:
    return work(**values)[128:168]


def history(sid: str, seq: int, date: int, rest: bytes = bytes(24)) -> bytes:
    return text(sid) + binary(seq) + binary(date) + rest


# ---------------------------------------------------------------- INSPACK
def inspack_cases() -> list[Case]:
    c = []

    def pk(cid: str, hexs: str, rule: str, note: str, **hand: int) -> None:
        c.append(Case(cid, "INSPACK", rule, note, arg=bytes.fromhex(hexs) + b"\xEE",
                      hand=hand))

    pk("PK001", "0000000000000C", "PK-VALID", "positive zero, sign C", R15=0)
    pk("PK002", "9999999999999C", "PK-VALID", "thirteen nines, sign C", R15=0)
    pk("PK003", "0000000012345D", "PK-VALID", "negative value, sign D", R15=0)
    pk("PK004", "0000000000000D", "PK-VALID", "negative zero, sign D", R15=0)
    pk("PK005", "0000000000123F", "PK-VALID", "unsigned/zoned-style sign F", R15=0)
    pk("PK006", "0000000000000F", "PK-VALID", "zero with sign F", R15=0)
    pk("PK007", "1234567890123C", "PK-VALID", "every digit value present", R15=0)
    pk("PK008", "0000000000000A", "PK-SIGN", "sign nibble A (positive alt) rejected", R15=8)
    pk("PK009", "0000000000000B", "PK-SIGN", "sign nibble B (negative alt) rejected", R15=8)
    pk("PK010", "0000000000000E", "PK-SIGN", "sign nibble E rejected", R15=8)
    pk("PK011", "00000000000000", "PK-SIGN", "sign nibble 0 rejected", R15=8)
    pk("PK012", "00000000000009", "PK-SIGN", "sign nibble 9 rejected", R15=8)
    pk("PK013", "A000000000000C", "PK-DIGIT", "high nibble of byte 0 is A", R15=8)
    pk("PK014", "0F00000000000C", "PK-DIGIT", "low nibble of byte 0 is F", R15=8)
    pk("PK015", "000000B000000C", "PK-DIGIT", "middle byte low nibble B", R15=8)
    pk("PK016", "00000000000F0C", "PK-DIGIT", "byte 5 low nibble F (F is only a sign)", R15=8)
    pk("PK017", "000000000000AC", "PK-DIGIT", "high nibble of sign byte is A", R15=8)
    pk("PK018", "FFFFFFFFFFFFFF", "PK-DIGIT", "all ones", R15=8)
    pk("PK019", "F0F0F0F0F0F0F0", "PK-DIGIT", "CP037 zoned digits are not packed", R15=8)
    pk("PK020", "A000000000000A", "PK-DIGIT", "bad digit and bad sign together", R15=8)
    return c


# ---------------------------------------------------------------- INSDATE
def insdate_cases() -> list[Case]:
    c = []

    def dt(cid: str, value: int, rule: str, note: str, **hand: int) -> None:
        w = work(WDATE=binary(value), WYEAR=SENT4, WMD=SENT4, WORD=SENT4,
                 WVALID=bytes.fromhex("11111111"))
        c.append(Case(cid, "INSDATE", rule, note, work=w, hand=hand))

    dt("DT001", 19000101, "DT-EPOCH", "first valid day; ordinal 0", WYEAR=1900, WMD=101, WORD=0)
    dt("DT002", 19000228, "DT-1900", "28 Feb 1900 valid", WORD=58)
    dt("DT003", 19000229, "DT-1900", "1900 is not a leap year (BR-002)", WVALID=8, WYEAR=1900)
    dt("DT004", 19000301, "DT-1900", "1 Mar 1900 ordinal skips no leap day", WORD=59)
    dt("DT005", 19001231, "DT-CONV", "last day of 1900", WORD=364)
    dt("DT006", 19010101, "DT-CONV", "year rollover", WORD=365)
    dt("DT007", 19040229, "DT-LEAP", "1904 leap day valid; 4*365+31+28 = 1519", WORD=1519)
    dt("DT008", 20000229, "DT-LEAP", "2000 leap day valid under contract rule")
    dt("DT009", 20240229, "DT-LEAP", "2024 leap day valid")
    dt("DT010", 20230229, "DT-LEAP", "2023 has no 29 Feb", WVALID=8)
    dt("DT011", 20230431, "DT-DAY", "April has 30 days", WVALID=8)
    dt("DT012", 20230430, "DT-DAY", "30 April valid")
    dt("DT013", 20231231, "DT-DAY", "31 December valid")
    dt("DT014", 20231301, "DT-MONTH", "month 13", WVALID=8)
    dt("DT015", 20230001, "DT-MONTH", "month 0", WVALID=8)
    dt("DT016", 20230100, "DT-DAY", "day 0", WVALID=8)
    dt("DT017", 20230132, "DT-DAY", "day 32", WVALID=8)
    dt("DT018", 20991231, "DT-RANGE", "last valid day; ordinal 73048", WORD=73048)
    dt("DT019", 21000101, "DT-RANGE", "year 2100 outside range; WYEAR still written",
       WVALID=8, WYEAR=2100)
    dt("DT020", 18991231, "DT-RANGE", "year 1899 outside range", WVALID=8, WYEAR=1899)
    dt("DT021", 0, "DT-SIGN", "zero: rejected before year split; WYEAR untouched", WVALID=8)
    dt("DT022", -1, "DT-SIGN", "negative: rejected before year split", WVALID=8)
    dt("DT023", 0x7FFFFFFF, "DT-RANGE", "max fullword: year 214748 rejected", WVALID=8,
       WYEAR=214748)
    dt("DT024", 21001301, "DT-PREC", "bad year and bad month: WYEAR written, WMD not",
       WVALID=8, WYEAR=2100)
    dt("DT025", 20250101, "DT-CONV", "ordinal used by the overflow case", WORD=45656)
    dt("DT026", 20200229, "DT-LEAP", "2020 leap day (age anniversary case)")
    return c


# ---------------------------------------------------------------- INSRATE
def insrate_cases() -> list[Case]:
    c = []

    def rt(cid: str, when: int, age: int, rule: str, note: str, **hand: int) -> None:
        w = work(TDATE=binary(when), WAGE=binary(age), WRATE=SENT3, WFEE=SENT3)
        c.append(Case(cid, "INSRATE", rule, note, work=w, hand=hand))

    rt("RT001", 18991231, 0, "RT-DOMAIN", "below first boundary: nothing written")
    rt("RT002", 19000101, 0, "RT-BAND1", "first boundary", WRATE=125, WFEE=700)
    rt("RT003", 19991231, 0, "RT-BAND1", "day before band 2", WRATE=125, WFEE=700)
    rt("RT004", 20000101, 0, "RT-BAND2", "band 2 start", WRATE=175, WFEE=600)
    rt("RT005", 20191231, 0, "RT-BAND2", "day before band 3", WRATE=175, WFEE=600)
    rt("RT006", 20200101, 0, "RT-BAND3", "band 3 start", WRATE=225, WFEE=500)
    rt("RT007", 20231231, 0, "RT-BAND3", "day before band 4", WRATE=225, WFEE=500)
    rt("RT008", 20240101, 0, "RT-BAND4", "band 4 start", WRATE=300, WFEE=400)
    rt("RT009", 20241231, 0, "RT-BAND4", "day before band 5", WRATE=300, WFEE=400)
    rt("RT010", 20250101, 0, "RT-BAND5", "band 5 start", WRATE=325, WFEE=350)
    rt("RT011", 20991231, 0, "RT-BAND5", "last contract day", WRATE=325, WFEE=350)
    rt("RT012", 99999999, 0, "RT-DOMAIN", "raw invalid date: INSRATE does not validate",
       WRATE=325, WFEE=350)
    rt("RT013", 0, 0, "RT-DOMAIN", "zero date: nothing written")
    rt("RT014", -1, 0, "RT-DOMAIN", "negative date: nothing written")
    rt("RT015", 20200101, 9, "RT-AGE", "age 9 keeps fee", WFEE=500)
    rt("RT016", 20200101, 10, "RT-AGE", "age 10 zeroes fee", WFEE=0)
    rt("RT017", 20200101, 11, "RT-AGE", "age 11 zeroes fee", WFEE=0)
    rt("RT018", 20200101, -1, "RT-AGE", "negative age is signed, keeps fee", WFEE=500)
    rt("RT019", 20200101, 0x7FFFFFFF, "RT-AGE", "huge age zeroes fee", WFEE=0)
    rt("RT020", 18991231, 10, "RT-AGE", "age 10 below domain: only WFEE zeroed", WFEE=0)
    return c


# ----------------------------------------------------------------- INSVAL
def insval_cases() -> list[Case]:
    c = []

    def vl(cid: str, rule: str, note: str, r15: int, **values: object) -> None:
        s = state(**values)  # type: ignore[arg-type]
        w = work(**s, WDATE=SENT4, WYEAR=SENT4, WMD=SENT4, WORD=SENT4,
                 WVALID=bytes.fromhex("11111111"))
        c.append(Case(cid, "INSVAL", rule, note, work=w, hand={"R15": r15}))

    vl("VL001", "VL-OK", "first state, all invariants hold", 0)
    vl("VL002", "VL-OK", "historical state with matching SLAST", 0,
       seq=3, date=20200601, last=history("00000001", 3, 20200601))
    vl("VL003", "VL-ID", "SID byte 0 is CP037 'A'", 8, raw_id=text("A0000001"))
    vl("VL004", "VL-ID", "SID byte 7 is CP037 '/'", 8, raw_id=text("0000000/"))
    vl("VL005", "VL-ID", "SID byte 3 is binary zero", 8, raw_id=text("000") + b"\0" + text("0001"))
    vl("VL006", "VL-ID", "SID all blanks", 8, raw_id=text("        "))
    vl("VL007", "VL-PAD", "SPAD last byte nonzero", 8, pad=bytes(6) + b"\x01")
    vl("VL008", "VL-TAIL", "STAIL last byte nonzero", 8, tail=bytes(39) + b"\x01")
    vl("VL009", "VL-SEQ", "negative sequence", 8, seq=-1)
    vl("VL010", "VL-SEQ", "max sequence with matching history", 0,
       seq=0x7FFFFFFF, last=history("00000001", 0x7FFFFFFF, 20200101))
    vl("VL011", "VL-PACK", "SFACE malformed digit", 8, face=bytes.fromhex("0000000A00000C"))
    vl("VL012", "VL-PACK", "SCASH bad sign A", 8, cash=bytes.fromhex("0000000050000A"))
    vl("VL013", "VL-PACK", "SLOAN all ones", 8, loan=bytes.fromhex("FF" * 7))
    vl("VL014", "VL-NEG", "SFACE negative", 8, face=packed(-1))
    vl("VL015", "VL-NEG", "SFACE negative zero accepted", 0, face=P0D)
    vl("VL016", "VL-NEG", "SCASH sign F accepted", 0, cash=packed(500_000, sign="F"))
    vl("VL017", "VL-MAX", "SFACE above MAXAMT", 8, face=packed(MAX_AMOUNT + 1))
    vl("VL018", "VL-MAX", "SFACE at MAXAMT", 0, face=packed(MAX_AMOUNT))
    vl("VL019", "VL-MAX", "SCASH above MAXAMT", 8, cash=packed(MAX_AMOUNT + 1))
    vl("VL020", "VL-LOAN", "SLOAN above SCASH", 8, loan=packed(500_001))
    vl("VL021", "VL-LOAN", "SLOAN equal to SCASH", 0, loan=packed(500_000))
    vl("VL022", "VL-DATE", "SISSUE invalid 30 Feb", 8, issue=20200230, date=20200301)
    vl("VL023", "VL-DATE", "SDATE invalid", 8, date=20201301)
    vl("VL024", "VL-ORDER", "SDATE before SISSUE", 8, date=20191231)
    vl("VL025", "VL-FIRST", "seq 0 with nonzero SLAST", 8, last=bytes(39) + b"\x01")
    vl("VL026", "VL-FIRST", "seq 0 with SDATE after SISSUE", 8, date=20200102)
    vl("VL027", "VL-HIST", "SLAST id differs", 8, seq=2, date=20200601,
       last=history("00000002", 2, 20200601))
    vl("VL028", "VL-HIST", "SLAST seq differs", 8, seq=2, date=20200601,
       last=history("00000001", 1, 20200601))
    vl("VL029", "VL-HIST", "SLAST date differs", 8, seq=2, date=20200601,
       last=history("00000001", 2, 20200531))
    vl("VL030", "VL-HIST", "SLAST tail bytes are not checked", 0, seq=2, date=20200601,
       last=history("00000001", 2, 20200601, bytes([0xFF]) * 24))
    vl("VL031", "VL-PREC", "bad id AND bad issue date: no INSDATE call (WDATE untouched)",
       8, raw_id=text("A0000001"), issue=20200230)
    vl("VL032", "VL-PREC", "loan>cash AND bad dates: date check never reached", 8,
       loan=packed(600_000), issue=20200230)
    vl("VL033", "VL-PREC", "valid issue, invalid SDATE: WDATE holds SDATE", 8,
       date=20200230)
    vl("VL034", "VL-PREC", "negative seq AND bad pad: same R15", 8, seq=-5,
       pad=b"\x01" + bytes(6))
    return c


# ---------------------------------------------------------------- INSCALC
def inscalc_cases() -> list[Case]:
    c = []

    def ca(cid: str, rule: str, note: str, s: dict | None = None, t: dict | None = None,
           flags: int = 0, tags: tuple[str, ...] = (), **hand: object) -> None:
        w = work(**(s or state()), **(t or txn()))
        c.append(Case(cid, "INSCALC", rule, note, work=w, flags=flags, tags=tags,
                      hand=hand))  # type: ignore[arg-type]

    # Operations on the base policy (cash 5,000.00, face 100,000.00, issue 2020-01-01)
    # 2020-01-01 -> 2021-01-01 is 366 days at 225 bps: 500000*225*366/3650000 = 11280.8 -> 11281
    ca("CA001", "CA-P", "premium: interest + premium committed", OSTAT="OKAY", OINT=11281,
       OCASH=611281, OCHG=30564, OSURR=580717, ODEATH=10_000_000, OAGE=1)
    ca("CA002", "CA-W", "withdrawal within funds", t=txn(op="W", amount=packed(1000)),
       OSTAT="OKAY", OCASH=510281)
    ca("CA003", "CA-W", "withdrawal exceeding cash+interest -> FUND, full rollback",
       t=txn(op="W", amount=packed(511282)), OSTAT="FUND", OINT=0, SCASH=500000)
    ca("CA004", "CA-W", "withdrawal of exactly cash+interest leaves zero",
       t=txn(op="W", amount=packed(511281)), OSTAT="OKAY", OCASH=0, OSURR=0)
    ca("CA005", "CA-L", "loan within cash", t=txn(op="L", amount=packed(200_000)),
       OSTAT="OKAY", OLOAN=200000, OSURR=285717, ODEATH=9_800_000)
    ca("CA006", "CA-L", "loan above cash -> FUND", t=txn(op="L", amount=packed(511282)),
       OSTAT="FUND")
    ca("CA007", "CA-L", "loan equal to cash+interest accepted, surrender floors at 0",
       t=txn(op="L", amount=packed(511281)), OSTAT="OKAY", OSURR=0)
    ca("CA008", "CA-R", "repayment within loan",
       s=state(loan=packed(300_000)), t=txn(op="R", amount=packed(100_000)),
       OSTAT="OKAY", OLOAN=200000)
    ca("CA009", "CA-R", "repayment above loan -> FUND (negative loan)",
       s=state(loan=packed(300_000)), t=txn(op="R", amount=packed(300_001)), OSTAT="FUND")
    ca("CA010", "CA-Q", "quote mutates SSEQ/SDATE/SLAST/SCASH by interest only",
       t=txn(op="Q", amount=P0), OSTAT="OKAY", OCASH=511281, SSEQ=1)
    ca("CA011", "CA-Q", "quote with nonzero amount -> AMNT", t=txn(op="Q", amount=packed(1)),
       OSTAT="AMNT")
    ca("CA012", "CA-D", "death quote: max(face,cash)-loan, policy stays open",
       s=state(loan=packed(100_000)), t=txn(op="D", amount=P0), OSTAT="OKAY",
       ODEATH=9_900_000, OLOAN=100000)
    ca("CA013", "CA-D", "death quote with amount -> AMNT", t=txn(op="D", amount=packed(5)),
       OSTAT="AMNT")
    ca("CA014", "CA-D", "death quote when cash exceeds face",
       s=state(face=packed(1000), cash=packed(5000)), t=txn(op="D", amount=P0),
       OSTAT="OKAY", ODEATH=5113, OINT=113)
    ca("CA015", "CA-TYPE", "unknown op X", t=txn(op="X", amount=P0), OSTAT="TYPE")
    ca("CA016", "CA-TYPE", "lower-case p is not P", t=txn(op="p"), OSTAT="TYPE")
    ca("CA017", "CA-TYPE", "blank op", t=txn(op=" "), OSTAT="TYPE")
    # Rejections before any calculation
    ca("CA018", "CA-STAT", "invalid state (bad id): OCASH/OLOAN stay zero",
       s=state(raw_id=text("A0000001")), OSTAT="STAT", OCASH=0)
    ca("CA019", "CA-STAT", "malformed SCASH nibble -> STAT",
       s=state(cash=bytes.fromhex("000000000A000C")), OSTAT="STAT")
    ca("CA020", "CA-NPOL", "transaction for another policy", t=txn(tid="00000002"),
       OSTAT="NPOL", OCASH=500000)
    ca("CA021", "CA-ORDR", "sequence 0", t=txn(seq=0), OSTAT="ORDR")
    ca("CA022", "CA-ORDR", "negative sequence", t=txn(seq=-3), OSTAT="ORDR")
    ca("CA023", "CA-ORDR", "sequence below state sequence",
       s=state(seq=5, date=20200601, last=history("00000001", 5, 20200601)),
       t=txn(seq=4, date=20200701), OSTAT="ORDR")
    ca("CA024", "CA-DUPL", "exact replay of SLAST",
       s=state(seq=5, date=20200601, last=trec(**txn(seq=5, date=20200601))),
       t=txn(seq=5, date=20200601), OSTAT="DUPL")
    ca("CA025", "CA-CNFL", "same sequence, different amount",
       s=state(seq=5, date=20200601, last=trec(**txn(seq=5, date=20200601))),
       t=txn(seq=5, date=20200601, amount=packed(100_001)), OSTAT="CNFL")
    ca("CA026", "CA-CNFL", "same sequence, differs only in tail byte",
       s=state(seq=5, date=20200601, last=trec(**txn(seq=5, date=20200601))),
       t=txn(seq=5, date=20200601, tail=bytes(12) + b"\x01"), OSTAT="CNFL")
    ca("CA027", "CA-FORM", "TPAD nonzero", t=txn(pad=b"\0\0\x01"), OSTAT="FORM")
    ca("CA028", "CA-FORM", "TTAIL nonzero", t=txn(tail=b"\x01" + bytes(12)), OSTAT="FORM")
    ca("CA029", "CA-PACK", "TAMT bad digit", t=txn(amount=bytes.fromhex("00000000000B0C")),
       OSTAT="PACK")
    ca("CA030", "CA-PACK", "TAMT bad sign", t=txn(amount=bytes.fromhex("0000000010000A")),
       OSTAT="PACK")
    ca("CA031", "CA-NEGA", "negative amount", t=txn(amount=packed(-1)), OSTAT="NEGA")
    ca("CA032", "CA-NEGA", "negative zero amount passes NEGA and adds nothing",
       t=txn(amount=P0D), OSTAT="OKAY", OCASH=511281)
    ca("CA033", "CA-PACK", "sign F amount is accepted numerically",
       t=txn(amount=packed(100_000, sign="F")), OSTAT="OKAY", OCASH=611281)
    ca("CA034", "CA-OVER", "amount above MAXAMT rejected before dates",
       t=txn(amount=packed(MAX_AMOUNT + 1)), OSTAT="OVER")
    ca("CA035", "CA-DATE", "invalid transaction date", t=txn(date=20210230), OSTAT="DATE")
    ca("CA036", "CA-DATE", "transaction before state date", t=txn(date=20191231),
       OSTAT="DATE")
    ca("CA037", "CA-DATE", "same-day transaction: zero days, zero interest",
       t=txn(date=20200101), OSTAT="OKAY", OINT=0, OCASH=600000)
    ca("CA038", "CA-PREC", "NPOL beats FORM/PACK/DATE", t=txn(tid="00000009", pad=b"\x01\0\0",
       amount=bytes.fromhex("FF" * 7), date=0), OSTAT="NPOL")
    ca("CA039", "CA-PREC", "FORM beats PACK", t=txn(pad=b"\x01\0\0",
       amount=bytes.fromhex("FF" * 7)), OSTAT="FORM")
    ca("CA040", "CA-PREC", "PACK beats DATE", t=txn(amount=bytes.fromhex("FF" * 7), date=0),
       OSTAT="PACK")
    ca("CA041", "CA-PREC", "NEGA beats DATE", t=txn(amount=packed(-5), date=0), OSTAT="NEGA")
    ca("CA042", "CA-PREC", "amount OVER beats DATE", t=txn(amount=packed(MAX_AMOUNT + 1),
       date=0), OSTAT="OVER")
    # Interest rounding ties (rate 125 bps, 365 days: interest = cash * 125 * 365 / 3650000)
    tie = state(issue=19900101, cash=packed(120), face=packed(1000))
    ca("CA043", "CA-TIE", "120 cents: 1.5 rounds half-up to 2", s=tie,
       t=txn(date=19910101, op="Q", amount=P0), OSTAT="OKAY", OINT=2, OCASH=122)
    ca("CA044", "CA-TIE", "40 cents: exactly 0.5 rounds to 1",
       s=state(issue=19900101, cash=packed(40), face=packed(1000)),
       t=txn(date=19910101, op="Q", amount=P0), OSTAT="OKAY", OINT=1)
    ca("CA045", "CA-TIE", "100 cents: 1.25 rounds down to 1",
       s=state(issue=19900101, cash=packed(100), face=packed(1000)),
       t=txn(date=19910101, op="Q", amount=P0), OSTAT="OKAY", OINT=1)
    ca("CA046", "CA-TIE", "39 cents: 0.4875 rounds to 0",
       s=state(issue=19900101, cash=packed(39), face=packed(1000)),
       t=txn(date=19910101, op="Q", amount=P0), OSTAT="OKAY", OINT=0)
    ca("CA047", "CA-TIE", "160 cents: 2.0 exact, no rounding",
       s=state(issue=19900101, cash=packed(160), face=packed(1000)),
       t=txn(date=19910101, op="Q", amount=P0), OSTAT="OKAY", OINT=2)
    # Surrender-charge rounding: charge = cash * fee / 10000 half-up (fee 500 bps)
    ca("CA048", "CA-CHG", "30 cents at 500 bps: 1.5 -> 2",
       s=state(cash=packed(30), face=packed(1000)), t=txn(date=20200101, op="Q", amount=P0),
       OSTAT="OKAY", OCHG=2, OSURR=28)
    ca("CA049", "CA-CHG", "10 cents at 500 bps: 0.5 -> 1",
       s=state(cash=packed(10), face=packed(1000)), t=txn(date=20200101, op="Q", amount=P0),
       OSTAT="OKAY", OCHG=1, OSURR=9)
    ca("CA050", "CA-CHG", "9 cents at 500 bps: 0.45 -> 0",
       s=state(cash=packed(9), face=packed(1000)), t=txn(date=20200101, op="Q", amount=P0),
       OSTAT="OKAY", OCHG=0, OSURR=9)
    ca("CA051", "CA-SURR", "charge+loan above cash floors surrender at 0",
       s=state(cash=packed(100), loan=packed(100), face=packed(1000)),
       t=txn(date=20200101, op="Q", amount=P0), OSTAT="OKAY", OSURR=0, OCHG=5)
    # Age boundaries (fee waived from age 10)
    ca("CA052", "CA-AGE", "age 9: day before tenth anniversary keeps fee",
       s=state(issue=20100601, date=20200101, seq=1, last=history("00000001", 1, 20200101)),
       t=txn(seq=2, date=20200531, op="Q", amount=P0), OSTAT="OKAY", OAGE=9, OFEE=500)
    ca("CA053", "CA-AGE", "age 10: tenth anniversary zeroes fee",
       s=state(issue=20100601, date=20200101, seq=1, last=history("00000001", 1, 20200101)),
       t=txn(seq=2, date=20200601, op="Q", amount=P0), OSTAT="OKAY", OAGE=10, OFEE=0, OCHG=0)
    ca("CA054", "CA-AGE", "29 Feb issue: 28 Feb ten years later is still age 9",
       s=state(issue=20200229), t=txn(date=20300228, op="Q", amount=P0), OSTAT="OKAY", OAGE=9)
    ca("CA055", "CA-AGE", "29 Feb issue: 1 Mar ten years later is age 10",
       s=state(issue=20200229), t=txn(date=20300301, op="Q", amount=P0), OSTAT="OKAY", OAGE=10)
    ca("CA056", "CA-AGE", "age 0 on the same day", t=txn(date=20200101, op="Q", amount=P0),
       OSTAT="OKAY", OAGE=0)
    # Rate boundaries seen through INSCALC (one day of interest each side)
    for cid, when, rate in (("CA057", 19991231, 125), ("CA058", 20000101, 175),
                            ("CA059", 20191231, 175), ("CA060", 20200101, 225),
                            ("CA061", 20231231, 225), ("CA062", 20240101, 300),
                            ("CA063", 20241231, 300), ("CA064", 20250101, 325)):
        sd = when - 1 if when % 100 != 1 else (when // 10000 - 1) * 10000 + 1231
        ca(cid, "CA-RATE", f"one day ending {when}: rate {rate}",
           s=state(issue=19900101, date=sd, seq=1, cash=packed(3_650_000),
                   last=history("00000001", 1, sd)),
           t=txn(seq=2, date=when, op="Q", amount=P0), OSTAT="OKAY", ORATE=rate, OINT=rate)
    # High values and overflow precedence
    big = state(issue=19000101, cash=packed(MAX_AMOUNT), face=packed(MAX_AMOUNT))
    ca("CA065", "CA-HIGH", "MAXAMT cash, zero days, zero premium accepted", s=big,
       t=txn(date=19000101, amount=P0), OSTAT="OKAY", OCASH=MAX_AMOUNT, OINT=0)
    ca("CA066", "CA-OVER", "MAXAMT cash plus one cent premium -> OVER after op", s=big,
       t=txn(date=19000101, amount=packed(1)), OSTAT="OVER", SCASH=MAX_AMOUNT)
    ca("CA067", "CA-OVER", "quotient overflow: 406526027393 interest > MAXAMT", s=big,
       t=txn(date=20250101, op="Q", amount=P0), OSTAT="OVER", OINT=0, WDAYS=45656)
    ca("CA068", "CA-PREC", "quotient OVER beats TYPE", s=big,
       t=txn(date=20250101, op="X", amount=P0), OSTAT="OVER")
    ca("CA069", "CA-OVER", "one day at 125 bps on MAXAMT (3424658) pushes cash over MAXAMT",
       s=big, t=txn(date=19000102, op="Q", amount=P0), OSTAT="OVER", OINT=0)
    ca("CA070", "CA-HIGH", "interest landing 793 cents below the cap commits",
       s=state(issue=20250101, cash=packed(99_991_095_889), face=packed(1000)),
       t=txn(date=20250102, op="Q", amount=P0), OSTAT="OKAY", OINT=8903317,
       OCASH=99_999_999_206)
    # Raw byte preservation of untouched state fields
    ca("CA071", "CA-RAW", "sign-F SCASH becomes canonical C after AP",
       s=state(cash=packed(500_000, sign="F")), t=txn(op="Q", amount=P0), OSTAT="OKAY")
    ca("CA072", "CA-RAW", "negative-zero SLOAN raw D preserved by Q; OLOAN canonical",
       s=state(loan=P0D), t=txn(op="Q", amount=P0), OSTAT="OKAY", OLOAN=0)
    ca("CA073", "CA-RAW", "negative-zero SLOAN normalised by L",
       s=state(loan=P0D), t=txn(op="L", amount=packed(7)), OSTAT="OKAY", OLOAN=7)
    ca("CA074", "CA-RAW", "sign-F SFACE never rewritten",
       s=state(face=packed(10_000_000, sign="F")), t=txn(op="Q", amount=P0), OSTAT="OKAY")
    ca("CA075", "CA-RAW", "sign-F SCASH left raw when STAT rejects (bad id)",
       s=state(cash=packed(500_000, sign="F"), raw_id=text("A0000001")), OSTAT="STAT")
    # Sequential reuse of one WORK area (flags: keep state, replace transaction)
    keep = FLAG_KEEP | FLAG_TXN
    ca("CA076", "CA-SEQ", "chain start: premium seq 1", t=txn(seq=1), tags=("chain",),
       OSTAT="OKAY")
    ca("CA077", "CA-SEQ", "second premium seq 2 on committed state", t=txn(seq=2, date=20210201),
       flags=keep, tags=("chain",), OSTAT="OKAY", SSEQ=2)
    ca("CA078", "CA-SEQ", "exact replay of seq 2 -> DUPL", t=txn(seq=2, date=20210201),
       flags=keep, tags=("chain",), OSTAT="DUPL")
    ca("CA079", "CA-SEQ", "seq 2 with different amount -> CNFL",
       t=txn(seq=2, date=20210201, amount=packed(1)), flags=keep, tags=("chain",), OSTAT="CNFL")
    ca("CA080", "CA-SEQ", "older seq 1 -> ORDR", t=txn(seq=1, date=20210301), flags=keep,
       tags=("chain",), OSTAT="ORDR")
    ca("CA081", "CA-SEQ", "death quote seq 3", t=txn(seq=3, date=20210301, op="D", amount=P0),
       flags=keep, tags=("chain",), OSTAT="OKAY")
    ca("CA082", "CA-SEQ", "premium after death quote accepted: quote did not close policy",
       t=txn(seq=4, date=20210401), flags=keep, tags=("chain",), OSTAT="OKAY", SSEQ=4)
    ca("CA083", "CA-SEQ", "failed withdrawal after chain leaves seq 4 state intact",
       t=txn(seq=5, date=20210501, op="W", amount=packed(MAX_AMOUNT)), flags=keep,
       tags=("chain",), OSTAT="FUND", SSEQ=4)
    ca("CA084", "CA-SEQ", "state overlay only: new policy with stale OREC/scratch present",
       s=state(sid="00000002", cash=packed(1000), face=packed(1000)),
       t=txn(tid="00000002", date=20200101, op="Q", amount=P0),
       flags=FLAG_KEEP | FLAG_STATE | FLAG_TXN, tags=("chain",), OSTAT="OKAY", OCASH=1000)
    return c


def all_cases() -> list[Case]:
    cases = inspack_cases() + insdate_cases() + insrate_cases() + insval_cases() + inscalc_cases()
    ids = [case.id for case in cases]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate case id")
    return cases
