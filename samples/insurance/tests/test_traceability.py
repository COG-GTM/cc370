"""Keep fixture case identity distinct from policy identity."""

import json
from pathlib import Path
import unittest

GOLDEN = Path(__file__).resolve().parents[1] / "golden/v1"


class TraceabilityTests(unittest.TestCase):
    def test_expected_rows_retain_unique_case_and_policy_identifiers(self) -> None:
        cases = [json.loads(line) for line in (GOLDEN / "cases.jsonl").read_text().splitlines()]
        expected = [json.loads(line) for line in (GOLDEN / "expected.jsonl").read_text().splitlines()]
        self.assertEqual(len(cases), len(expected))
        self.assertEqual(len({case["id"] for case in cases}), len(cases))
        for case, result in zip(cases, expected):
            self.assertEqual(result["case_id"], case["id"])
            transaction = bytes.fromhex(case["transaction_hex"])
            self.assertEqual(result["id"], transaction[:8].decode("cp037"))
            self.assertEqual(result["seq"], int.from_bytes(transaction[8:12], "big", signed=True))


if __name__ == "__main__":
    unittest.main()
