"""V001 record codecs. Binary data is never routed through text translation."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Field:
    name: str
    offset: int
    size: int
    kind: str


STATE = [
    Field("id", 0, 8, "text"), Field("issue", 8, 4, "binary"),
    Field("date", 12, 4, "binary"), Field("seq", 16, 4, "binary"),
    Field("face", 20, 7, "packed"), Field("cash", 27, 7, "packed"),
    Field("loan", 34, 7, "packed"), Field("reserved", 41, 7, "hex"),
    Field("last_request", 48, 40, "hex"), Field("tail", 88, 40, "hex"),
]
TRANSACTION = [
    Field("id", 0, 8, "text"), Field("seq", 8, 4, "binary"),
    Field("date", 12, 4, "binary"), Field("op", 16, 1, "text"),
    Field("reserved", 17, 3, "hex"), Field("amount", 20, 7, "packed"),
    Field("tail", 27, 13, "hex"),
]
RESULT = [
    Field("id", 0, 8, "text"), Field("seq", 8, 4, "binary"),
    Field("date", 12, 4, "binary"), Field("status", 16, 4, "text"),
    Field("op", 20, 1, "text"), Field("reserved", 21, 3, "hex"),
    Field("age", 24, 4, "binary"), Field("rate", 28, 3, "packed"),
    Field("fee", 31, 3, "packed"), Field("cash", 34, 7, "packed"),
    Field("surrender", 41, 7, "packed"), Field("death", 48, 7, "packed"),
    Field("loan", 55, 7, "packed"), Field("interest", 62, 7, "packed"),
    Field("charge", 69, 7, "packed"), Field("version", 76, 4, "text"),
    Field("tail", 80, 16, "hex"),
]
SCHEMAS = {"state": (128, STATE), "transaction": (40, TRANSACTION),
           "result": (96, RESULT)}


def packed(value: int, size: int = 7, sign: str = "") -> bytes:
    digits = str(abs(value))
    if len(digits) > size * 2 - 1:
        raise ValueError(f"packed({value}, {size}) overflows")
    final = sign or ("D" if value < 0 else "C")
    if final not in ("C", "D", "F") or (value < 0 and final != "D"):
        raise ValueError("invalid packed sign request")
    return bytes.fromhex(digits.zfill(size * 2 - 1) + final)


def unpacked(raw: bytes) -> int:
    text = raw.hex()
    if not text or text[-1] not in "cdf" or not text[:-1].isdigit():
        raise ValueError(f"invalid signed packed decimal: {raw.hex()}")
    magnitude = int(text[:-1])
    return -magnitude if text[-1] == "d" else magnitude


def binary(value: int) -> bytes:
    return value.to_bytes(4, "big", signed=True)


def number(raw: bytes) -> int:
    return int.from_bytes(raw, "big", signed=True)


def text(value: str) -> bytes:
    return value.encode("cp037")


def decode(raw: bytes, kind: str) -> dict[str, int | str]:
    size, fields = SCHEMAS[kind]
    if len(raw) != size:
        raise ValueError(f"{kind}: length {len(raw)}, expected {size}")
    result: dict[str, int | str] = {}
    for field in fields:
        data = raw[field.offset:field.offset + field.size]
        if field.kind == "packed":
            result[field.name] = unpacked(data)
        elif field.kind == "binary":
            result[field.name] = number(data)
        elif field.kind == "text":
            result[field.name] = data.decode("cp037")
        else:
            result[field.name] = data.hex()
    return result


def records(raw: bytes, kind: str) -> list[bytes]:
    size = SCHEMAS[kind][0]
    if len(raw) % size:
        raise ValueError(f"truncated {kind}: {len(raw)} bytes, LRECL={size}")
    return [raw[i:i + size] for i in range(0, len(raw), size)]


def state(policy: str, issue: int, face: int, cash: int, loan: int = 0) -> bytes:
    if len(policy) != 8 or not policy.isascii() or not policy.isdigit():
        raise ValueError("policy must be eight ASCII digits before CP037 encoding")
    return (text(policy) + binary(issue) + binary(issue) + binary(0)
            + packed(face) + packed(cash) + packed(loan) + bytes(87))


def transaction(policy: str, seq: int, date: int, op: str, amount: int) -> bytes:
    raw = (text(policy) + binary(seq) + binary(date) + text(op) + bytes(3)
           + packed(amount) + bytes(13))
    if len(raw) != 40:
        raise ValueError("transaction length")
    return raw
