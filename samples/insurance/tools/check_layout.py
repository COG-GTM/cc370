"""Check assembled DSECT offsets against every host codec field."""

from pathlib import Path

from codec import SCHEMAS

SYMBOLS = {
    "state": ("SREC", ["SID", "SISSUE", "SDATE", "SSEQ", "SFACE", "SCASH",
                       "SLOAN", "SPAD", "SLAST", "STAIL"]),
    "transaction": ("TREC", ["TID", "TSEQ", "TDATE", "TOP", "TPAD", "TAMT", "TTAIL"]),
    "result": ("OREC", ["OID", "OSEQ", "ODATE", "OSTAT", "OOP", "OPAD",
                       "OAGE", "ORATE", "OFEE", "OCASH", "OSURR", "ODEATH",
                       "OLOAN", "OINT", "OCHG", "OVERS", "OTAIL"]),
}


def verify(path: Path) -> None:
    symbols = {}
    for line in path.read_text().splitlines():
        if not line.startswith("#"):
            row = line.split("\t")
            symbols[row[0]] = (int(row[1]), int(row[2]))
    for kind, (base_symbol, names) in SYMBOLS.items():
        length, fields = SCHEMAS[kind]
        base, size = symbols[base_symbol]
        if size != length or len(names) != len(fields):
            raise ValueError(f"{kind} record size differs from assembled DSECT")
        for name, field in zip(names, fields):
            if symbols[name] != (base + field.offset, field.size):
                raise ValueError(f"{name} assembled offset/length differs from {kind}.{field.name}")
    if symbols["WNUM"][0] % 8:
        raise ValueError("CVD scratch area is not doubleword aligned")
    print(f"DSECT and codec layouts: PASS ({path.name})")
