"""Host tests validate scaffolding and contracts, never stand in for MVS execution."""

from datetime import date
from fractions import Fraction
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

from codec import (SCHEMAS, binary, decode, packed, state,  # noqa: E402
                   transaction, unpacked)
from compare import compare, validate_receipt  # noqa: E402
from corpus import fixtures  # noqa: E402
from oracle import MAX_AMOUNT, batch, evaluate  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


class ContractTests(unittest.TestCase):
    def test_audited_anchors(self) -> None:
        anchors = json.loads((ROOT / "tests/anchors.json").read_text())["anchors"]
        for anchor in anchors:
            with self.subTest(anchor=anchor["id"]):
                before = state("00000001", anchor["issue"], anchor["face"],
                               anchor["cash"], anchor["loan"])
                txn = transaction("00000001", 1, anchor["date"], anchor["op"], anchor["amount"])
                after, result = evaluate(before, txn)
                fields = decode(result, "result")
                self.assertEqual(fields["status"], anchor["status"])
                if fields["status"] == "OKAY":
                    for key in ("age", "rate", "fee", "interest", "charge", "surrender", "death"):
                        self.assertEqual(fields[key], anchor[key], key)
                    self.assertEqual(fields["cash"], anchor["out_cash"])
                    self.assertEqual(fields["loan"], anchor["out_loan"])
                else:
                    self.assertEqual(before, after)

    def test_codec_limits_signs_and_invalid_nibbles(self) -> None:
        for value in (0, -1, 1, MAX_AMOUNT, -MAX_AMOUNT, 9999999999999):
            self.assertEqual(unpacked(packed(value)), value)
        self.assertEqual(unpacked(packed(0, sign="D")), 0)
        self.assertEqual(unpacked(packed(12, sign="F")), 12)
        with self.assertRaises(ValueError):
            packed(10000000000000)
        for position in range(7):
            raw = bytearray(packed(17))
            raw[position] = 0xFA
            with self.subTest(position=position), self.assertRaises(ValueError):
                unpacked(bytes(raw))
        with self.assertRaises(ValueError):
            unpacked(bytes.fromhex("0000000000000a"))

    def test_layouts_cover_every_byte_once(self) -> None:
        for length, fields in SCHEMAS.values():
            occupied = [i for field in fields for i in range(field.offset, field.offset + field.size)]
            self.assertEqual(occupied, list(range(length)))

    def test_duplicate_conflict_order_and_validation_are_immutable(self) -> None:
        initial = state("00000001", 20240101, 1000000, 100000)
        txn = transaction("00000001", 10, 20250101, "P", 10000)
        committed, result = evaluate(initial, txn)
        self.assertEqual(decode(result, "result")["status"], "OKAY")
        requests = [
            (txn, "DUPL"),
            (transaction("00000001", 10, 20250101, "P", 10001), "CNFL"),
            (transaction("00000001", 9, 20250101, "Q", 0), "ORDR"),
            (transaction("00000001", 11, 20240230, "Q", 0), "DATE"),
            (transaction("00000001", 11, 20250101, "W", 9999999), "FUND"),
        ]
        for request, status in requests:
            after, raw = evaluate(committed, request)
            self.assertEqual(after, committed)
            self.assertEqual(decode(raw, "result")["status"], status)

    def test_invalid_master_and_truncation(self) -> None:
        master = state("00000001", 20240101, 1000000, 100000)
        for malformed in (master + master, master[:-1], master[:27] + bytes(7) + master[34:]):
            with self.assertRaises(ValueError):
                batch(malformed, b"")
        with self.assertRaises(ValueError):
            batch(master, b"\x00")

    def test_actual_order_is_not_sorted(self) -> None:
        master = state("00000001", 20240101, 1000000, 100000)
        first = transaction("00000001", 1, 20250101, "P", 10000)
        second = transaction("00000001", 2, 20250101, "L", 5000)
        forward, _ = batch(master, first + second)
        reversed_master, results = batch(master, second + first)
        self.assertNotEqual(forward, reversed_master)
        self.assertEqual(decode(results[96:], "result")["status"], "ORDR")

    def test_decimal_against_independent_rational_math(self) -> None:
        # Integer/Fraction rounding does not call Decimal or the oracle rounder.
        for cash in (1, 50, 100, 12500, 100000, 99999999, 90000000000):
            for effective in (20240101, 20240102, 20240229, 20241231, 20250101):
                master = state("00000001", 20240101, MAX_AMOUNT, cash)
                _, raw = evaluate(master, transaction("00000001", 1, effective, "Q", 0))
                out = decode(raw, "result")
                end = date(effective // 10000, effective // 100 % 100, effective % 100)
                days = (end - date(2024, 1, 1)).days
                rate, fee = (300, 400) if effective < 20250101 else (325, 350)
                fraction = Fraction(cash * rate * days, 3650000)
                interest = (2 * fraction.numerator + fraction.denominator) // (
                    2 * fraction.denominator
                )
                self.assertEqual(out["interest"], interest)
                charge = ((cash + interest) * fee * 2 + 10000) // 20000
                self.assertEqual(out["charge"], charge)

    def test_frozen_corpus_and_restarts(self) -> None:
        files = fixtures()
        for name, raw in files.items():
            self.assertEqual((ROOT / "golden/v1" / name).read_bytes(), raw, name)
        # Interrupt after an arbitrary record, then resume from a completed
        # checkpoint: the oracle's final state equals uninterrupted processing.
        txns = files["a.txns.bin"]
        split = 1777 * 40
        checkpoint, out1 = batch(files["polin.bin"], txns[:split])
        final, out2 = batch(checkpoint, txns[split:])
        self.assertEqual(final, files["a.expected.pol.bin"])
        self.assertEqual(out1 + out2, files["a.expected.res.bin"])


class ComparatorTests(unittest.TestCase):
    def setUp(self) -> None:
        _, self.result = evaluate(state("00000001", 20250101, 1000, 100),
                                  transaction("00000001", 1, 20250101, "Q", 0))

    def test_every_field_is_compared(self) -> None:
        compare(self.result, self.result, "result")
        for field in SCHEMAS["result"][1]:
            bad = bytearray(self.result)
            bad[field.offset] ^= 1
            with self.subTest(field=field.name), self.assertRaisesRegex(ValueError, field.name):
                compare(self.result, bytes(bad), "result")

    def test_missing_duplicate_unexpected_truncated_reordered(self) -> None:
        other = self.result[:8] + binary(2) + self.result[12:]
        expected = self.result + other
        for bad in (self.result, expected + self.result, expected[:-1], other + self.result, b""):
            with self.assertRaises(ValueError):
                compare(expected, bad, "result")

    def test_packed_sign_changes_fail_even_when_value_equal(self) -> None:
        bad = self.result[:40] + bytes([self.result[40] | 3]) + self.result[41:]
        with self.assertRaisesRegex(ValueError, "cash"):
            compare(self.result, bad, "result")

    def test_receipt_fails_closed(self) -> None:
        good = {"schema": "insurance-run-v1", "job_id": "HOST-TEST-FIXTURE",
                "outcome": "completed", "timed_out": False, "abend": None,
                "step_rc": {"RUN": 0}, "polin_sha256": "a" * 64}
        validate_receipt(good, {"polin_sha256": "a" * 64})
        for key, value in (("outcome", "running"), ("timed_out", True),
                           ("abend", "S0C7"), ("step_rc", {"RUN": 4}),
                           ("step_rc", {}), ("step_rc", {"RUN": False}),
                           ("polin_sha256", "b" * 64), ("job_id", "")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_receipt({**good, key: value}, {"polin_sha256": "a" * 64})
        with self.assertRaises(ValueError):
            validate_receipt({}, {})

    def test_master_fields_are_compared(self) -> None:
        raw = state("00000001", 20250101, 1000, 100)
        for field in SCHEMAS["state"][1]:
            bad = bytearray(raw)
            bad[field.offset] ^= 1
            with self.subTest(field=field.name), self.assertRaisesRegex(ValueError, field.name):
                compare(raw, bytes(bad), "state")


if __name__ == "__main__":
    unittest.main()
