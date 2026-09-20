"""Create a new deterministic golden version, or verify the frozen v1 bytes."""

import argparse
from collections import Counter
from datetime import timedelta
import hashlib
import json
from pathlib import Path
import random

from codec import decode, number, packed, records, state, transaction
from oracle import MAX_AMOUNT, RATES, batch, calendar, evaluate

ROOT = Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "golden/v1"
SEED = 37020260920
ISSUES = [19000228, 19991231, 20000228, 20140228, 20191231,
          20200229, 20231231, 20240228, 20241231, 20981231]


def as_integer_date(base: int, days: int) -> int:
    value = calendar(base) + timedelta(days=days)
    return value.year * 10000 + value.month * 100 + value.day


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()


def json_lines(values: list[dict[str, object]]) -> bytes:
    return "".join(json.dumps(v, sort_keys=True) + "\n" for v in values).encode()


def fixtures() -> dict[str, bytes]:
    rng = random.Random(SEED)
    policies = []
    for i in range(256):
        cash = [100, 12500, MAX_AMOUNT, MAX_AMOUNT - 100, 0][i % 5] if i < 30 else (
            rng.randrange(10_000, 900_000_000)
        )
        face = min(MAX_AMOUNT, max(cash, rng.randrange(1_000_000, 9_000_000_000)))
        loan = cash // 8 if i % 3 == 0 else 0
        policies.append(state(f"{i + 1:08d}", ISSUES[i % len(ISSUES)], face, cash, loan))
    initial = b"".join(policies)
    files = {"polin.bin": initial, "rates.json": json_bytes(RATES)}
    files["expectation-provenance.json"] = json_bytes({
        "kind": "generated expectations, not captured execution",
        "sha256": {
            name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
            for name in ["tools/codec.py", "tools/oracle.py", "tools/corpus.py",
                         "tests/anchors.json"]
        },
    })
    cases: list[dict[str, object]] = []
    expected: list[dict[str, object]] = []
    stages: list[dict[str, object]] = []
    overall_status: Counter[str] = Counter()
    current = initial
    for stage, start, stop in [("a", 0, 16), ("b", 16, 32)]:
        stage_input = current
        rows = records(current, "state")
        requests = []
        results = []
        for event in range(start, stop):
            order = list(range(256))
            rng.shuffle(order)
            for index in order:
                raw = rows[index]
                policy = raw[:8].decode("cp037")
                issue, prior = number(raw[8:12]), number(raw[12:16])
                seq = (event + 1) * 10
                date = as_integer_date(issue, [0, 1, 2, 2, 29, 365][min(event, 5)])
                op, amount, category = "Q", 0, "valuation"
                if event == 0:
                    op, amount, category = "P", 10000 + index * 37, "premium"
                elif event == 1:
                    category = "date-rate-boundary"
                elif event == 2:
                    op, amount, category = "L", 100 + index, "loan"
                elif event == 3:
                    op, amount, category = "R", 75 + index, "repayment"
                elif event == 4:
                    op, amount, category = "W", 1000 + index * 3, "withdrawal"
                elif event == 5:
                    op, category = "D", "death"
                elif event == 6:
                    category = "duplicate"
                elif event == 7:
                    category = "conflicting-duplicate"
                elif event == 8:
                    seq, category = max(1, number(raw[16:20]) - 1), "stale-sequence"
                elif event == 9:
                    op, amount, category = "P", -1, "negative-sign"
                elif event == 10:
                    category = "malformed-digit"
                elif event == 11:
                    date, category = 20240230, "invalid-calendar"
                elif event == 12:
                    date, category = as_integer_date(prior, -1), "backdated"
                elif event == 13:
                    op, date, category = "X", prior, "invalid-operation"
                elif event == 14:
                    amount, category = MAX_AMOUNT + 1, "amount-overflow"
                elif event == 15:
                    date = as_integer_date(issue, 366)
                    op, amount, category = "P", 99, "anniversary"
                elif event == 16:
                    date, category = prior, "negative-zero"
                elif event == 17:
                    date, op, amount, category = prior, "P", 1, "unsigned-packed-F"
                elif event == 18:
                    category = "malformed-sign"
                elif event == 19:
                    category = "reserved-byte"
                elif event == 20:
                    op, amount, date, category = "L", MAX_AMOUNT, prior, "loan-limit"
                elif event == 21:
                    op, amount, date, category = "R", MAX_AMOUNT, prior, "repay-limit"
                elif event == 22:
                    op, amount, date, category = "W", MAX_AMOUNT, prior, "withdraw-limit"
                elif event == 23:
                    seq, category = 0, "zero-sequence"
                elif event == 24:
                    date, category = prior, "sequence-gap"
                elif event == 25:
                    seq, category = 1, "out-of-order"
                elif event == 26:
                    date, amount, category = prior, 1, "quote-nonzero"
                elif event == 27:
                    date, category = 21000101, "date-upper-bound"
                elif event == 28:
                    date, category = as_integer_date(issue, 3653), "duration-ten"
                elif event == 29:
                    date, op, amount, category = prior, "P", 1, "cent-rounding"
                elif event == 30:
                    date, op, category = prior, "D", "cash-vs-face"
                elif event == 31:
                    date, category = as_integer_date(issue, 4018), "final-valuation"
                txn = transaction(policy, seq, date, op, amount)
                if event in (6, 7) and number(raw[16:20]):
                    txn = raw[48:88]
                    if event == 7:
                        txn = txn[:20] + packed(777) + txn[27:]
                if event == 10:
                    txn = txn[:20] + b"\xfa" + txn[21:]
                elif event == 16:
                    txn = txn[:20] + packed(0, sign="D") + txn[27:]
                elif event == 17:
                    txn = txn[:20] + packed(1, sign="F") + txn[27:]
                elif event == 18:
                    txn = txn[:26] + b"\x0a" + txn[27:]
                elif event == 19:
                    txn = txn[:17] + b"\x01" + txn[18:]
                rows[index], result = evaluate(raw, txn)
                result_fields = decode(result, "result")
                case_id = f"V1-{stage.upper()}-{event:02d}-{policy}"
                cases.append({"id": case_id, "stage": stage,
                              "record": len(requests) + 1, "category": category,
                              "transaction_hex": txn.hex()})
                expected.append({"case_id": case_id, **result_fields})
                overall_status[str(result_fields["status"])] += 1
                requests.append(txn)
                results.append(result)
        # Missing-policy coverage is a real driver path rather than a core shortcut.
        missing = transaction("99999999", 1, 20250101, "Q", 0)
        requests.append(missing)
        current, all_results = batch(stage_input, b"".join(requests))
        assert all_results[:-96] == b"".join(results)
        assert current == b"".join(rows)
        cases.append({"id": f"V1-{stage.upper()}-MISSING", "stage": stage,
                      "record": len(requests), "category": "missing-policy",
                      "transaction_hex": missing.hex()})
        expected.append({"case_id": f"V1-{stage.upper()}-MISSING",
                         **decode(all_results[-96:], "result")})
        overall_status["NPOL"] += 1
        files[f"{stage}.txns.bin"] = b"".join(requests)
        files[f"{stage}.expected.pol.bin"] = current
        files[f"{stage}.expected.res.bin"] = all_results
        stages.append({"name": stage, "input_master": "polin.bin" if stage == "a"
                       else "a.expected.pol.bin", "transactions": f"{stage}.txns.bin",
                       "expected_master": f"{stage}.expected.pol.bin",
                       "expected_results": f"{stage}.expected.res.bin",
                       "transactions_count": len(requests), "policies_count": 256})
        replay = b"".join(raw[48:88] for raw in rows if number(raw[16:20]))
        replay_master, replay_results = batch(current, replay)
        assert replay_master == current
        assert all(decode(r, "result")["status"] == "DUPL"
                   for r in records(replay_results, "result"))
        files[f"{stage}.replay.txns.bin"] = replay
        files[f"{stage}.replay.expected.res.bin"] = replay_results
        stages.append({"name": f"{stage}-replay", "input_master": f"{stage}.expected.pol.bin",
                       "transactions": f"{stage}.replay.txns.bin",
                       "expected_master": f"{stage}.expected.pol.bin",
                       "expected_results": f"{stage}.replay.expected.res.bin",
                       "transactions_count": len(replay) // 40, "policies_count": 256})
    # A full rerun against the original generation must be byte-identical.
    full_master, full_results = batch(initial, files["a.txns.bin"] + files["b.txns.bin"])
    assert full_master == current
    assert full_results == files["a.expected.res.bin"] + files["b.expected.res.bin"]
    anchors = json.loads((ROOT / "tests/anchors.json").read_text())["anchors"]
    anchor_master, anchor_txns = [], []
    for index, anchor in enumerate(anchors, 1):
        policy = f"{index:08d}"
        anchor_master.append(state(policy, anchor["issue"], anchor["face"],
                                   anchor["cash"], anchor["loan"]))
        anchor_txns.append(transaction(policy, 1, anchor["date"], anchor["op"],
                                       anchor["amount"]))
    anchor_pol, anchor_res = batch(b"".join(anchor_master), b"".join(anchor_txns))
    files["anchors.polin.bin"] = b"".join(anchor_master)
    files["anchors.txns.bin"] = b"".join(anchor_txns)
    files["anchors.expected.pol.bin"], files["anchors.expected.res.bin"] = anchor_pol, anchor_res
    stages.append({"name": "anchors", "input_master": "anchors.polin.bin",
                   "transactions": "anchors.txns.bin",
                   "expected_master": "anchors.expected.pol.bin",
                   "expected_results": "anchors.expected.res.bin",
                   "transactions_count": len(anchors), "policies_count": len(anchors)})
    files["cases.jsonl"], files["expected.jsonl"] = json_lines(cases), json_lines(expected)
    files["coverage.json"] = json_bytes({
        "seed": SEED, "version": 1, "policies": 256, "primary_cases": len(cases),
        "categories": dict(Counter(str(c["category"]) for c in cases)),
        "statuses": dict(overall_status), "stages": stages,
        "provenance": "Synthetic independent Decimal expectations, NOT MVS observations",
        "anchors_sha256": hashlib.sha256((ROOT / "tests/anchors.json").read_bytes()).hexdigest(),
    })
    files["SHA256SUMS"] = "".join(
        f"{hashlib.sha256(content).hexdigest()}  {name}\n"
        for name, content in sorted(files.items())
    ).encode()
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["generate", "verify"])
    parser.add_argument("--output", type=Path, default=GOLDEN)
    args = parser.parse_args()
    files = fixtures()
    if args.mode == "generate":
        if any(args.output.iterdir()):
            parser.error("refusing to overwrite a golden version; use a new empty directory")
        for name, raw in files.items():
            (args.output / name).write_bytes(raw)
        print(f"Generated {len(files)} immutable expectation artifacts")
    else:
        actual_names = {p.name for p in args.output.iterdir() if p.is_file()}
        if actual_names != set(files):
            raise SystemExit(f"golden inventory differs: {actual_names ^ set(files)}")
        for name, raw in files.items():
            if (args.output / name).read_bytes() != raw:
                raise SystemExit(f"golden mismatch: {name}; version changes require review")
        print(f"Verified {len(files)} frozen artifacts against deterministic regeneration")


if __name__ == "__main__":
    main()
