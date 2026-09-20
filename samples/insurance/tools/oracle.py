"""Independent Decimal/date-library expectation model, NOT an app replacement."""

from datetime import date
from decimal import Decimal, ROUND_HALF_UP

from codec import binary, number, packed, records, text, unpacked

MAX_AMOUNT = 99_999_999_999
RATES = [
    (19000101, 125, 700),
    (20000101, 175, 600),
    (20200101, 225, 500),
    (20240101, 300, 400),
    (20250101, 325, 350),
]


def calendar(value: int) -> date:
    year, md = divmod(value, 10000)
    month, day = divmod(md, 100)
    if not 1900 <= year <= 2099:
        raise ValueError("date outside contract")
    return date(year, month, day)


def rounded(value: Decimal) -> int:
    return int(value.quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def validate_state(raw: bytes) -> None:
    if len(raw) != 128:
        raise ValueError("state length")
    if any(not 0xF0 <= b <= 0xF9 for b in raw[:8]):
        raise ValueError("policy id")
    if raw[41:48] != bytes(7) or raw[88:] != bytes(40):
        raise ValueError("reserved bytes")
    seq = number(raw[16:20])
    if seq < 0:
        raise ValueError("negative sequence")
    face, cash, loan = [unpacked(raw[x:x + 7]) for x in (20, 27, 34)]
    if any(not 0 <= value <= MAX_AMOUNT for value in (face, cash, loan)):
        raise ValueError("state amount")
    if loan > cash:
        raise ValueError("undercollateralized state")
    issue, last = calendar(number(raw[8:12])), calendar(number(raw[12:16]))
    if last < issue:
        raise ValueError("last date precedes issue")
    if seq == 0:
        if raw[48:88] != bytes(40) or issue != last:
            raise ValueError("initial state checkpoint")
    elif (raw[48:56] != raw[:8] or raw[56:60] != raw[16:20]
          or raw[60:64] != raw[12:16]):
        raise ValueError("last request checkpoint")


def evaluate(master: bytes, txn: bytes) -> tuple[bytes, bytes]:
    """Return candidate state and one fully populated 96-byte result."""
    if len(txn) != 40:
        raise ValueError("transaction length")
    output = bytearray(96)
    output[:16] = txn[:16]
    output[20:21] = txn[16:17]
    output[76:80] = text("V001")
    for offset, width in [(28, 3), (31, 3), *[(i, 7) for i in range(34, 70, 7)]]:
        output[offset:offset + width] = packed(0, width)

    def rejected(status: str) -> tuple[bytes, bytes]:
        output[16:20] = text(status)
        return master, bytes(output)

    try:
        validate_state(master)
    except ValueError:
        return rejected("STAT")
    cash = unpacked(master[27:34])
    loan = unpacked(master[34:41])
    output[34:41], output[55:62] = packed(cash), packed(loan)
    if txn[:8] != master[:8]:
        return rejected("NPOL")
    seq, old_seq = number(txn[8:12]), number(master[16:20])
    if seq <= 0 or seq < old_seq:
        return rejected("ORDR")
    if seq == old_seq:
        return rejected("DUPL" if txn == master[48:88] else "CNFL")
    if txn[17:20] != bytes(3) or txn[27:] != bytes(13):
        return rejected("FORM")
    try:
        amount = unpacked(txn[20:27])
    except ValueError:
        return rejected("PACK")
    if amount < 0:
        return rejected("NEGA")
    if amount > MAX_AMOUNT:
        return rejected("OVER")
    try:
        effective = calendar(number(txn[12:16]))
    except ValueError:
        return rejected("DATE")
    previous = calendar(number(master[12:16]))
    if effective < previous:
        return rejected("DATE")
    issue = calendar(number(master[8:12]))
    age = effective.year - issue.year - (
        (effective.month, effective.day) < (issue.month, issue.day)
    )
    _, rate, fee = max(row for row in RATES if row[0] <= number(txn[12:16]))
    if age >= 10:
        fee = 0
    interest = rounded(
        Decimal(cash) * Decimal(rate) / Decimal(10000)
        * Decimal((effective - previous).days) / Decimal(365)
    )
    cash += interest
    if interest > MAX_AMOUNT or cash > MAX_AMOUNT:
        return rejected("OVER")
    operation = txn[16:17].decode("cp037")
    if operation == "P":
        cash += amount
    elif operation == "W":
        cash -= amount
    elif operation == "L":
        loan += amount
    elif operation == "R":
        loan -= amount
    elif operation in ("Q", "D"):
        if amount:
            return rejected("AMNT")
    else:
        return rejected("TYPE")
    if min(cash, loan) < 0 or loan > cash:
        return rejected("FUND")
    if max(cash, loan) > MAX_AMOUNT:
        return rejected("OVER")
    charge = rounded(Decimal(cash) * Decimal(fee) / Decimal(10000))
    surrender = max(0, cash - charge - loan)
    death = max(0, max(unpacked(master[20:27]), cash) - loan)
    output[16:20] = text("OKAY")
    output[24:28] = binary(age)
    output[28:31], output[31:34] = packed(rate, 3), packed(fee, 3)
    for offset, value in zip(range(34, 70, 7),
                             [cash, surrender, death, loan, interest, charge]):
        output[offset:offset + 7] = packed(value)
    updated = bytearray(master)
    updated[12:16], updated[16:20] = txn[12:16], txn[8:12]
    updated[27:34] = packed(cash)
    if operation in ("L", "R"):
        updated[34:41] = packed(loan)
    updated[48:88] = txn
    return bytes(updated), bytes(output)


def batch(master: bytes, transactions: bytes) -> tuple[bytes, bytes]:
    policies = records(master, "state")
    if len(policies) > 512:
        raise ValueError("master exceeds 512 policies")
    for index, raw in enumerate(policies):
        validate_state(raw)
        if index and raw[:8] <= policies[index - 1][:8]:
            raise ValueError("master IDs must be strictly increasing")
    lookup = {raw[:8]: i for i, raw in enumerate(policies)}
    output = []
    for txn in records(transactions, "transaction"):
        if txn[:8] not in lookup:
            _, result = evaluate(bytes(128), txn)
            result = result[:16] + text("NPOL") + result[20:]
        else:
            index = lookup[txn[:8]]
            policies[index], result = evaluate(policies[index], txn)
        output.append(result)
    return b"".join(policies), b"".join(output)
