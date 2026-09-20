"""Render guest JCL only; submission and dataset transport belong to the runner."""

import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def dataset(value: str) -> str:
    if len(value) > 44 or not re.fullmatch(
        r"[A-Z@$#][A-Z0-9@$#-]{0,7}(?:\.[A-Z@$#][A-Z0-9@$#-]{0,7})*", value
    ):
        raise argparse.ArgumentTypeError(f"invalid MVS dataset: {value}")
    return value


def token(value: str) -> str:
    if not re.fullmatch(r"[A-Z0-9]+", value):
        raise argparse.ArgumentTypeError("job-card values must be uppercase alphanumeric")
    return value


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["build", "run"])
    parser.add_argument("--hlq", type=dataset, required=True)
    parser.add_argument("--account", type=token, required=True)
    parser.add_argument("--job-class", type=token, default="A")
    parser.add_argument("--msg-class", type=token, default="A")
    parser.add_argument("--syslib", type=dataset, action="append", default=[])
    parser.add_argument("--polin", type=dataset)
    parser.add_argument("--txnin", type=dataset)
    parser.add_argument("--run", type=dataset)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if len(args.job_class) != 1 or len(args.msg_class) != 1:
        parser.error("job/message classes must be one character")
    if args.mode == "build" and not args.syslib:
        parser.error("build requires exact --syslib guest datasets in search order")
    if args.mode == "run" and not all([args.polin, args.txnin, args.run]):
        parser.error("run requires --polin, --txnin, and a new --run output prefix")
    for suffix in (".SRC", ".MAC", ".OBJ", ".LOAD"):
        dataset(args.hlq + suffix)
    if args.run:
        for suffix in (".POL", ".RES"):
            dataset(args.run + suffix)
        if args.polin in (args.run + ".POL", args.run + ".RES"):
            parser.error("output must not name the input generation")
        if args.txnin in (args.run + ".POL", args.run + ".RES"):
            parser.error("output must not name the input transactions")
    values = {
        "HLQ": args.hlq, "ACCOUNT": args.account, "CLASS": args.job_class,
        "MSGCLASS": args.msg_class,
        "SYSLIB": "\n".join(f"//         DD DSN={d},DISP=SHR" for d in args.syslib),
        "POLIN": args.polin or "", "TXNIN": args.txnin or "", "RUN": args.run or "",
    }
    output = (ROOT / "jcl" / f"{args.mode}.jcl").read_text()
    for name, value in values.items():
        output = output.replace(f"@{name}@", value)
    for number, line in enumerate(output.splitlines(), 1):
        if len(line) > 71 or not line.isascii():
            parser.error(f"rendered JCL line {number} exceeds column 71 or is not ASCII")
    with args.output.open("x") as stream:
        stream.write(output)
    print(f"Rendered {args.output}; guest submission NOT performed")


if __name__ == "__main__":
    main()
