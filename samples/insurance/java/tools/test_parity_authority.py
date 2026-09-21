#!/usr/bin/env python3
"""Negative controls for parity_java.Authority (a2/a3 guest-receipt binding).

Every check runs against a scratch copy of the archived A2 evidence for one path
(samples/insurance/evidence/28790e2/runtime/ifox-iewl) and the frozen golden directory, mutates
exactly ONE thing, and asserts that stage_bytes() refuses the authority. The unmodified copy is
the positive control. No Java is executed; nothing outside the scratch directory is written.

    python3 -m unittest samples/insurance/java/tools/test_parity_authority.py
"""

from __future__ import annotations

import hashlib
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import parity_java as pj  # noqa: E402

SAMPLE = HERE.parent.parent
GOLDEN = SAMPLE / "golden" / "v1"
A2_RUNTIME = SAMPLE / "evidence" / "28790e2" / "runtime"
PATH = "ifox-iewl"
STAGES = ("anchors", "a", "a-replay", "b", "b-replay")


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


class AuthorityBindingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="parity-authority-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.runtime = self.tmp / "runtime"
        shutil.copytree(A2_RUNTIME / PATH, self.runtime / PATH)
        shutil.copytree(A2_RUNTIME / f"{PATH}-provenance", self.runtime / f"{PATH}-provenance")
        self.golden = self.tmp / "golden"
        shutil.copytree(GOLDEN, self.golden)
        self.root = self.runtime / PATH
        self.prov = self.runtime / f"{PATH}-provenance"

    # ------------------------------------------------------------------ helpers

    def authority(self, **kw) -> pj.Authority:
        return pj.Authority("a2", self.root, self.golden, **kw)

    def receipt(self, stage: str) -> dict:
        return json.loads((self.root / stage / "receipt.json").read_text())

    def write_receipt(self, stage: str, receipt: dict) -> None:
        (self.root / stage / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")

    def rewrite_golden_inventory(self) -> None:
        """Re-sign the scratch golden inventory so only the intended mutation is visible."""
        lines = []
        for line in (self.golden / "SHA256SUMS").read_text().splitlines():
            _, name = line.split("  ", 1)
            lines.append(f"{sha((self.golden / name).read_bytes())}  {name}")
        (self.golden / "SHA256SUMS").write_text("\n".join(lines) + "\n")

    def assert_rejected(self, stage: str, fragment: str, **kw) -> None:
        with self.assertRaises(pj.AuthorityError) as ctx:
            self.authority(**kw).stage_bytes(stage)
        self.assertIn(fragment, str(ctx.exception))

    # ------------------------------------------------------------------ positive control

    def test_unmodified_archive_is_accepted_on_every_stage(self) -> None:
        auth = self.authority()
        for stage in STAGES:
            data = auth.stage_bytes(stage)
            policies, txns = auth.counts(stage)
            self.assertEqual(len(data["polout"]), policies * 128)
            self.assertEqual(len(data["resout"]), txns * 96)
            self.assertIn(stage, auth.receipts)
        prov = auth.provenance()
        self.assertEqual(prov["build_manifest_sha256"],
                         sha((self.prov / "build.json").read_bytes()))
        self.assertEqual(prov["guest_manifest_sha256"],
                         sha((self.prov / "guest.json").read_bytes()))
        self.assertEqual(prov["rates_sha256"], sha((self.golden / "rates.json").read_bytes()))

    def test_explicit_manifest_paths_are_accepted(self) -> None:
        elsewhere = self.tmp / "elsewhere"
        elsewhere.mkdir()
        shutil.copy(self.prov / "build.json", elsewhere / "b.json")
        shutil.copy(self.prov / "guest.json", elsewhere / "g.json")
        shutil.rmtree(self.prov)
        auth = self.authority(build_manifest=elsewhere / "b.json",
                              guest_manifest=elsewhere / "g.json")
        auth.stage_bytes("a")

    # ------------------------------------------------------------------ provenance manifests

    def test_missing_build_manifest_fails_closed(self) -> None:
        (self.prov / "build.json").unlink()
        self.assert_rejected("a", "missing build manifest")

    def test_missing_guest_manifest_fails_closed(self) -> None:
        (self.prov / "guest.json").unlink()
        self.assert_rejected("a", "missing guest manifest")

    def test_build_manifest_of_another_backend_is_refused(self) -> None:
        other = A2_RUNTIME / "as370-iewl-provenance" / "build.json"
        shutil.copy(other, self.prov / "build.json")
        self.assert_rejected("a", "is for backend 'as370-iewl'")

    def test_modified_build_manifest_breaks_the_receipt_binding(self) -> None:
        doc = json.loads((self.prov / "build.json").read_text())
        doc["job"]["job_id"] = "JOB99999"
        (self.prov / "build.json").write_text(json.dumps(doc, indent=2) + "\n")
        self.assert_rejected("a", "receipt hash mismatch: build_manifest_sha256")

    def test_modified_guest_manifest_breaks_the_receipt_binding(self) -> None:
        doc = json.loads((self.prov / "guest.json").read_text())
        doc["distribution"] = "another guest"
        (self.prov / "guest.json").write_text(json.dumps(doc, indent=2) + "\n")
        self.assert_rejected("a", "receipt hash mismatch: guest_manifest_sha256")

    # ------------------------------------------------------------------ frozen rate table

    def test_changed_rate_table_breaks_the_receipt_binding(self) -> None:
        rows = json.loads((self.golden / "rates.json").read_text())
        rows[0][1] += 1
        (self.golden / "rates.json").write_text(json.dumps(rows) + "\n")
        self.rewrite_golden_inventory()  # only the rate binding is left to detect it
        self.assert_rejected("a", "receipt hash mismatch: rates_sha256")

    def test_modified_golden_artifact_is_refused_by_the_inventory(self) -> None:
        f = self.golden / "a.txns.bin"
        raw = bytearray(f.read_bytes())
        raw[0] ^= 0x01
        f.write_bytes(bytes(raw))
        with self.assertRaises(pj.AuthorityError) as ctx:
            self.authority()
        self.assertIn("modified or missing golden artifact: a.txns.bin", str(ctx.exception))

    # ------------------------------------------------------------------ pinned golden inputs

    def test_observed_polin_that_differs_from_the_golden_input_is_refused(self) -> None:
        f = self.root / "a" / "polin.bin"
        raw = bytearray(f.read_bytes())
        raw[20] ^= 0x01
        f.write_bytes(bytes(raw))
        self.assert_rejected("a", "polin.bin differs from the pinned golden input master")

    def test_observed_txnin_that_differs_from_the_golden_input_is_refused(self) -> None:
        f = self.root / "a" / "txnin.bin"
        raw = bytearray(f.read_bytes())
        raw[20] ^= 0x01
        f.write_bytes(bytes(raw))
        self.assert_rejected("a", "txnin.bin differs from the pinned golden transactions")

    def test_receipt_input_hash_is_checked_against_golden_not_the_receipt(self) -> None:
        r = self.receipt("a")
        r["polin_sha256"] = "0" * 64
        self.write_receipt("a", r)
        self.assert_rejected("a", "receipt hash mismatch: polin_sha256")
        r = self.receipt("a-replay")
        r["txnin_sha256"] = "0" * 64
        self.write_receipt("a-replay", r)
        self.assert_rejected("a-replay", "receipt hash mismatch: txnin_sha256")

    # ------------------------------------------------------------------ receipt keys

    def test_receipt_lacking_any_of_the_seven_hash_keys_is_refused(self) -> None:
        for key in pj.GUEST_RECEIPT_HASH_KEYS:
            with self.subTest(key=key):
                self.setUp()
                r = self.receipt("b")
                del r[key]
                self.write_receipt("b", r)
                self.assert_rejected("b", f"guest receipt lacks ['{key}']")

    def test_receipt_with_wrong_value_for_any_hash_key_is_refused(self) -> None:
        for key in pj.GUEST_RECEIPT_HASH_KEYS:
            with self.subTest(key=key):
                self.setUp()
                r = self.receipt("b")
                r[key] = "f" * 64
                self.write_receipt("b", r)
                self.assert_rejected("b", f"receipt hash mismatch: {key}")

    # ------------------------------------------------------------------ observed outputs/receipt

    def test_altered_observed_output_is_refused(self) -> None:
        f = self.root / "b" / "resout.bin"
        raw = bytearray(f.read_bytes())
        raw[16] ^= 0x01
        f.write_bytes(bytes(raw))
        self.assert_rejected("b", "receipt hash mismatch: resout_sha256")

    def test_missing_observed_file_is_refused(self) -> None:
        (self.root / "b" / "polout.bin").unlink()
        self.assert_rejected("b", "missing")

    def test_missing_receipt_is_refused(self) -> None:
        (self.root / "b" / "receipt.json").unlink()
        self.assert_rejected("b", "missing guest receipt")

    def test_failed_or_abended_or_nonzero_rc_receipt_is_refused(self) -> None:
        mutations = {
            "outcome": ("outcome", "failed", "run did not complete without timeout"),
            "timed_out": ("timed_out", True, "run did not complete without timeout"),
            "abend": ("abend", "S0C7", "ABEND or missing ABEND evidence"),
            "step_rc": ("step_rc", {"RUN": 12}, "nonzero/invalid step RC"),
            "schema": ("schema", "other", "missing/unsupported run receipt schema"),
        }
        for name, (key, value, fragment) in mutations.items():
            with self.subTest(mutation=name):
                self.setUp()
                r = self.receipt("b")
                r[key] = value
                self.write_receipt("b", r)
                self.assert_rejected("b", fragment)

    def test_truncated_observed_output_is_refused_even_with_a_matching_receipt(self) -> None:
        f = self.root / "b" / "resout.bin"
        raw = f.read_bytes()[:-96]
        f.write_bytes(raw)
        r = self.receipt("b")
        r["resout_sha256"] = sha(raw)
        self.write_receipt("b", r)
        self.assert_rejected("b", "transaction/result bytes are not")


TARGETED = HERE.parent / "evidence" / "acceptance" / "targeted"
POSITIVE_CASE, CONTROL_CASE = "interest-tie", "control-master-513"


class TargetedAuthorityBindingTest(unittest.TestCase):
    """Same discipline for targeted_a3.py compare: scratch copy of the retained targeted
    authority, cases regenerated deterministically, one mutation per check."""

    @classmethod
    def setUpClass(cls) -> None:
        import targeted_a3
        cls.ta = targeted_a3
        cls.cases_src = Path(tempfile.mkdtemp(prefix="targeted-cases-")) / "cases"
        targeted_a3.write_cases(cls.cases_src)

    @classmethod
    def tearDownClass(cls) -> None:
        shutil.rmtree(cls.cases_src.parent, True)

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="targeted-authority-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        shutil.copytree(TARGETED / "authority", self.tmp / "authority")
        shutil.copytree(TARGETED / "provenance", self.tmp / "provenance")
        shutil.copytree(self.cases_src, self.tmp / "cases")
        self.golden = self.tmp / "golden"
        shutil.copytree(GOLDEN, self.golden)
        self.authority, self.prov_dir, self.cases = (self.tmp / "authority",
                                                     self.tmp / "provenance", self.tmp / "cases")
        self.index = {c["name"]: c for c in json.loads((self.cases / "cases.json").read_text())}
        self.load_prefix = json.loads((self.authority / "capture.json").read_text())["load_prefix"]

    def prov(self, build: Path | None = None, guest: Path | None = None) -> dict:
        return self.ta.load_provenance(build or self.prov_dir / "build.json",
                                       guest or self.prov_dir / "guest.json",
                                       self.golden, self.load_prefix)

    def validate(self, case: str, prov: dict | None = None):
        return self.ta.validate_case_authority(self.index[case], self.authority, self.cases,
                                               prov or self.prov())

    def receipt(self, case: str) -> dict:
        return json.loads((self.authority / case / "receipt.json").read_text())

    def write_receipt(self, case: str, receipt: dict) -> None:
        (self.authority / case / "receipt.json").write_text(json.dumps(receipt) + "\n")

    def assert_rejected(self, case: str, fragment: str, prov: dict | None = None) -> None:
        with self.assertRaises(pj.AuthorityError) as ctx:
            self.validate(case, prov)
        self.assertIn(fragment, str(ctx.exception))

    # positive controls -------------------------------------------------------------------

    def test_retained_targeted_authority_is_accepted_for_every_case(self) -> None:
        prov = self.prov()
        for name, case in self.index.items():
            receipt, data = self.validate(name, prov)
            self.assertEqual(receipt["case"], name)
            self.assertEqual(data["polin"], (self.cases / name / "polin.bin").read_bytes())
            if case["control"]:
                self.assertEqual(receipt["step_rc"], {"RUN": 12})
            else:
                self.assertEqual(receipt["step_rc"], {"RUN": 0})
        self.assertEqual(prov["build_manifest_sha256"],
                         sha((self.prov_dir / "build.json").read_bytes()))

    # provenance --------------------------------------------------------------------------

    def test_missing_manifests_fail_closed(self) -> None:
        (self.prov_dir / "build.json").unlink()
        with self.assertRaises(pj.AuthorityError) as ctx:
            self.prov()
        self.assertIn("missing build manifest", str(ctx.exception))
        shutil.copy(TARGETED / "provenance" / "build.json", self.prov_dir / "build.json")
        (self.prov_dir / "guest.json").unlink()
        with self.assertRaises(pj.AuthorityError) as ctx:
            self.prov()
        self.assertIn("missing guest manifest", str(ctx.exception))

    def test_build_manifest_of_another_load_library_is_refused(self) -> None:
        other = A2_RUNTIME / "as370-iewl-provenance" / "build.json"
        with self.assertRaises(pj.AuthorityError) as ctx:
            self.prov(build=other)
        self.assertIn("is for load library", str(ctx.exception))

    def test_modified_manifests_break_the_receipt_binding(self) -> None:
        for name, key in (("build.json", "build_manifest_sha256"),
                          ("guest.json", "guest_manifest_sha256")):
            with self.subTest(manifest=name):
                self.setUp()
                doc = json.loads((self.prov_dir / name).read_text())
                doc["x_note"] = "altered"
                (self.prov_dir / name).write_text(json.dumps(doc) + "\n")
                self.assert_rejected(POSITIVE_CASE, f"receipt hash mismatch: {key}")
                self.assert_rejected(CONTROL_CASE, f"receipt hash mismatch: {key}")

    def test_changed_rate_table_breaks_the_receipt_binding(self) -> None:
        rows = json.loads((self.golden / "rates.json").read_text())
        rows[0][1] += 1
        (self.golden / "rates.json").write_text(json.dumps(rows) + "\n")
        self.assert_rejected(POSITIVE_CASE, "receipt hash mismatch: rates_sha256")
        self.assert_rejected(CONTROL_CASE, "receipt hash mismatch: rates_sha256")

    # pinned inputs -----------------------------------------------------------------------

    def test_authority_input_that_differs_from_the_pinned_case_is_refused(self) -> None:
        for k in ("polin", "txnin"):
            with self.subTest(file=k):
                self.setUp()
                f = self.authority / POSITIVE_CASE / f"{k}.bin"
                raw = bytearray(f.read_bytes())
                raw[20] ^= 0x01
                f.write_bytes(bytes(raw))
                self.assert_rejected(POSITIVE_CASE, f"authority {k}.bin differs from the pinned")

    def test_cases_dir_input_that_differs_from_cases_json_is_refused(self) -> None:
        f = self.cases / POSITIVE_CASE / "txnin.bin"
        raw = bytearray(f.read_bytes())
        raw[20] ^= 0x01
        f.write_bytes(bytes(raw))
        self.assert_rejected(POSITIVE_CASE, "cases-dir txnin.bin differs from cases.json")

    # receipt keys, both kinds ------------------------------------------------------------

    def test_receipt_lacking_or_wrong_on_any_hash_key_is_refused(self) -> None:
        for case in (POSITIVE_CASE, CONTROL_CASE):
            for key in pj.GUEST_RECEIPT_HASH_KEYS:
                with self.subTest(case=case, key=key, mutation="missing"):
                    self.setUp()
                    r = self.receipt(case)
                    del r[key]
                    self.write_receipt(case, r)
                    self.assert_rejected(case, f"guest receipt lacks ['{key}']")
                with self.subTest(case=case, key=key, mutation="wrong"):
                    self.setUp()
                    r = self.receipt(case)
                    r[key] = "f" * 64
                    self.write_receipt(case, r)
                    self.assert_rejected(case, f"receipt hash mismatch: {key}")

    def test_receipt_for_another_case_is_refused(self) -> None:
        r = self.receipt("quotient-over")
        self.write_receipt(POSITIVE_CASE, r)
        self.assert_rejected(POSITIVE_CASE, "is for another case/kind")

    def test_receipt_statuses_that_disagree_with_resout_are_refused(self) -> None:
        r = self.receipt(POSITIVE_CASE)
        r["observed_statuses"][0] = "OVER"
        self.write_receipt(POSITIVE_CASE, r)
        self.assert_rejected(POSITIVE_CASE, "observed_statuses differ from the observed RESOUT")

    # positive case: legacy validator rules -----------------------------------------------

    def test_positive_case_with_failed_timed_out_abended_or_rc12_receipt_is_refused(self) -> None:
        mutations = {
            "outcome": ("outcome", "failed", "run did not complete without timeout"),
            "timed_out": ("timed_out", True, "run did not complete without timeout"),
            "abend": ("abend", "S0C7", "ABEND or missing ABEND evidence"),
            "rc12": ("step_rc", {"RUN": 12}, "nonzero/invalid step RC"),
            "schema": ("schema", "insurance-a3-targeted-v1", "missing/unsupported run receipt"),
        }
        for name, (key, value, fragment) in mutations.items():
            with self.subTest(mutation=name):
                self.setUp()
                r = self.receipt(POSITIVE_CASE)
                r[key] = value
                self.write_receipt(POSITIVE_CASE, r)
                self.assert_rejected(POSITIVE_CASE, fragment)

    # control case: explicit control validator --------------------------------------------

    def test_control_the_guest_accepted_is_not_rejection_evidence(self) -> None:
        for name, patch in {
            "rc0": {"step_rc": {"RUN": 0}},
            "completed": {"outcome": "completed"},
            "passed": {"passed": True},
            "extra_step": {"step_rc": {"RUN": 12, "SMOKE": 0}},
            "rc8": {"step_rc": {"RUN": 8}},
        }.items():
            with self.subTest(mutation=name):
                self.setUp()
                r = self.receipt(CONTROL_CASE)
                r.update(patch)
                self.write_receipt(CONTROL_CASE, r)
                with self.assertRaises(pj.AuthorityError) as ctx:
                    self.validate(CONTROL_CASE)
                self.assertRegex(str(ctx.exception),
                                 "not rejected by the guest|RUN RC is not exactly 12")

    def test_control_receipt_keeps_timeout_abend_and_schema_fail_closed(self) -> None:
        mutations = {
            "timed_out": ("timed_out", True, "timed out"),
            "abend": ("abend", "S0C4", "ABEND or missing ABEND evidence"),
            "schema": ("schema", "other", "missing/unsupported run receipt schema"),
            "job_id": ("job_id", "", "missing job_id"),
        }
        for name, (key, value, fragment) in mutations.items():
            with self.subTest(mutation=name):
                self.setUp()
                r = self.receipt(CONTROL_CASE)
                r[key] = value
                self.write_receipt(CONTROL_CASE, r)
                self.assert_rejected(CONTROL_CASE, fragment)

    def test_control_with_missing_abend_key_is_refused(self) -> None:
        r = self.receipt(CONTROL_CASE)
        del r["abend"]
        self.write_receipt(CONTROL_CASE, r)
        self.assert_rejected(CONTROL_CASE, "ABEND or missing ABEND evidence")


if __name__ == "__main__":
    unittest.main()
