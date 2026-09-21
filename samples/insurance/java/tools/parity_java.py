#!/usr/bin/env python3
"""Java parity driver: run one Java execution mode over every corpus stage against ONE authority
and compare byte-for-byte with tools/compare.py's comparator.

Modes (one per run, never mixed):
  batch      the batch CLI adapter (`java -jar ledger-app.jar batch run ...`) with the file store
  http-gen   the stateful HTTP generation path: every request is POSTed as raw 40-byte hex to
             /requests:raw against a running ledger-app backed by PostgreSQL
  http-json  the stateful hybrid HTTP path: requests that this driver can represent as typed JSON
             go to /requests, every byte-nonrepresentable request (F sign, negative zero, nonzero
             reserved bytes, malformed packed data, non-CP037 identifier, date outside the ISO
             grammar or any record that would not re-encode identically) falls back to
             /requests:raw. Physical order is preserved; typed coverage is reported separately.

Authorities (never mixed inside one run):
  a1  generated expectations   samples/insurance/golden/v1 (coverage.json, cases.jsonl, *.bin)
  a2  archived observed output  <evidence>/runtime/<path>/<stage>/{polin,txnin,polout,resout}.bin
  a3  fresh observed output     same layout as a2, produced by a fresh Hercules/MVS run

Each mode keeps its own state chain: stage N+1 is seeded from the Java generation published for
stage N (the service refuses to seed if that generation's bytes differ from the authority's input
master for stage N+1). Anchors and A are independent roots (separate namespaces). The chain stops
at the first failing stage. tools/compare.py is imported unchanged; Java receipts are validated
here, with every hash recomputed from the actual bytes rather than trusted from the receipt.

Observed authorities (a2/a3) are accepted only after their guest receipt passes the UNCHANGED
legacy validator (compare.validate_receipt) with the SAME seven hashes tools/compare.py's main()
recomputes, every one from bytes read here, none trusted from the receipt: polin/txnin from the
pinned golden input files of the stage (the same-directory polin.bin/txnin.bin must be identical
to them), polout/resout from the observed files, build_manifest/guest_manifest from the path's
provenance files (<authority-dir>/../<path>-provenance/{build,guest}.json or the explicit
--build-manifest/--guest-manifest), rates from golden/rates.json. The golden inventory
(SHA256SUMS) is verified first. A missing provenance file, a build manifest for another backend,
a receipt lacking any of the seven keys, a missing/failed/timed-out/ABENDed/nonzero-RC receipt, a
missing observed file, a hash mismatch or a count that disagrees with coverage.json stops the run
before any Java code is executed (see test_parity_authority.py for the per-key negative checks).

HTTP modes additionally check, per request: the echoed request bytes equal the bytes sent, the
ordinal is the physical position, the typed/raw flag equals the driver's own decision, the raw
top-level status equals the status decoded from the returned bytes, the returned result bytes
equal the authority's result record for that position (field/raw differences are reported and
the stage FAILS in both HTTP modes even if the persisted RESOUT is later found correct), and for
typed responses every JSON result field equals the field decoded here from the returned result
bytes (matching recordHex alone is not accepted).

Working directory: run from anywhere, all paths are explicit.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
SAMPLE = HERE.parent.parent  # samples/insurance
sys.path.insert(0, str(SAMPLE / "tools"))

import codec  # noqa: E402  (unchanged legacy record codec: field offsets only)
from compare import differences, validate_receipt  # noqa: E402  (unchanged legacy comparator)

CHAIN = ["a", "a-replay", "b", "b-replay"]
ROOTS = {"anchors": ["anchors"], "a": CHAIN}
RECEIPT_SCHEMA = "insurance-java-run-v1"
MANIFEST_SCHEMA = "insurance-expected-manifest-v1"
RECORD_SCHEMA = "POLIN/POLOUT FB128, TXNIN FB40, RESOUT FB96, CP037"
MODES = ("batch", "http-gen", "http-json")
PRINTABLE = re.compile(r"^[\x20-\x7e]+$")
TYPED_RESULT_FIELDS = ("id", "seq", "status", "op", "age", "rate", "fee", "cash", "surrender",
                       "death", "loan", "interest", "charge", "version")


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def rate_table_sha(rates_json: Path) -> str:
    """Canonical form the Java receipt binds: 'effective,rate_bps,fee_bps\\n' per row."""
    rows = json.loads(rates_json.read_text())
    return sha("".join(f"{e},{r},{f}\n" for e, r, f in rows).encode("ascii"))


# ---------------------------------------------------------------------------- typed decision


def typed_view(record: bytes) -> dict | None:
    """The driver's OWN decision whether a 40-byte request is byte-representable as typed JSON.

    Independent of the Java TypedCodec: decode with the legacy codec's offsets, re-encode here and
    require identity. Anything that fails (F sign, negative zero, nonzero reserved/tail bytes,
    malformed digits, non-printable or non-round-tripping CP037 text, date outside YYYY-MM-DD with
    a 4-digit year / month 01-12 / day 01-31) is sent raw.
    """
    if len(record) != 40:
        return None
    if record[17:20] != b"\0\0\0" or record[27:40] != bytes(13):
        return None
    ident, op = record[0:8], record[16:17]
    try:
        id_text, op_text = ident.decode("cp037"), op.decode("cp037")
    except UnicodeDecodeError:
        return None
    if not PRINTABLE.match(id_text) or not PRINTABLE.match(op_text):
        return None
    if id_text.encode("cp037") != ident or op_text.encode("cp037") != op:
        return None
    amount_hex = record[20:27].hex()
    if amount_hex[-1] not in "cd" or not amount_hex[:-1].isdigit():
        return None
    magnitude = int(amount_hex[:-1])
    if amount_hex[-1] == "d" and magnitude == 0:
        return None  # negative zero: numerically zero, byte-distinct
    amount = -magnitude if amount_hex[-1] == "d" else magnitude
    seq = codec.number(record[8:12])
    ymd = codec.number(record[12:16])
    if ymd < 0:
        return None
    y, m, d = ymd // 10000, (ymd // 100) % 100, ymd % 100
    if y > 9999 or not 1 <= m <= 12 or not 1 <= d <= 31:
        return None
    view = {"id": id_text, "seq": seq, "date": f"{y:04d}-{m:02d}-{d:02d}", "op": op_text,
            "amount": amount}
    again = codec.transaction(id_text, seq, ymd, op_text, amount)
    return view if again == record else None


def typed_result_errors(response: dict, expected_hex: str) -> list[str]:
    """Independently decode the returned result bytes and compare every typed JSON field."""
    errors = []
    typed = response.get("result")
    if not isinstance(typed, dict):
        return ["typed response carries no result object"]
    raw = bytes.fromhex(response["resultHex"])
    decoded = codec.decode(raw, "result")
    for field in TYPED_RESULT_FIELDS:
        if typed.get(field) != decoded[field]:
            errors.append(f"result.{field}: json {typed.get(field)!r} != bytes {decoded[field]!r}")
    if typed.get("dateYmd") != decoded["date"]:
        errors.append(f"result.dateYmd: json {typed.get('dateYmd')!r} != bytes {decoded['date']!r}")
    ymd = decoded["date"]
    iso = (f"{ymd // 10000:04d}-{(ymd // 100) % 100:02d}-{ymd % 100:02d}"
           if 0 <= ymd <= 99991231 and 1 <= (ymd // 100) % 100 <= 12 and 1 <= ymd % 100 <= 31
           else None)
    if typed.get("date") != iso:
        errors.append(f"result.date: json {typed.get('date')!r} != {iso!r}")
    if response.get("status") != decoded["status"]:
        errors.append(f"status: json {response.get('status')!r} != bytes {decoded['status']!r}")
    if (typed.get("recordHex") or "").lower() != expected_hex:
        errors.append("result.recordHex differs from resultHex")
    return errors


def response_errors(resp: dict, record: bytes, position: int, view: dict | None,
                    expected_result: bytes) -> list[str]:
    """Every check applied to ONE immediate HTTP response; any entry fails the stage.

    The returned result bytes are compared with the authority's result record for the same
    physical position right here, so a wrong immediate response cannot be hidden by a correct
    persisted RESOUT read back after publication.
    """
    errs = []
    if (resp.get("requestHex") or "").lower() != record.hex():
        errs.append("echoed request bytes differ from the bytes sent")
    if resp.get("ordinal") != position + 1:
        errs.append(f"ordinal {resp.get('ordinal')} != physical position {position + 1}")
    if bool(resp.get("typed")) != (view is not None):
        errs.append("typed flag differs from the driver's decision")
    result_hex = (resp.get("resultHex") or "").lower()
    if len(result_hex) != 192 or not re.fullmatch(r"[0-9a-f]{192}", result_hex):
        errs.append(f"resultHex is not 96 bytes: {result_hex[:40]!r}")
        return errs
    got = bytes.fromhex(result_hex)
    decoded = codec.decode(got, "result")
    if resp.get("status") != decoded["status"]:
        errs.append(f"status: json {resp.get('status')!r} != bytes {decoded['status']!r}")
    if resp.get("accepted") != (decoded["status"] == "OKAY"):
        errs.append(f"accepted: json {resp.get('accepted')!r} for status {decoded['status']!r}")
    if got != expected_result:
        for d in differences(expected_result, got, "result", {1: f"position {position}"}):
            errs.append("immediate result differs from authority: " + json.dumps(d, sort_keys=True))
    if view is not None:
        errs += typed_result_errors(resp, result_hex)
    return errs


# ---------------------------------------------------------------------------- authorities


class AuthorityError(Exception):
    """The observed authority could not be trusted; nothing was compared."""


GUEST_RECEIPT_HASH_KEYS = ("polin_sha256", "txnin_sha256", "polout_sha256", "resout_sha256",
                          "build_manifest_sha256", "guest_manifest_sha256", "rates_sha256")


def verify_golden_inventory(golden: Path) -> None:
    """Same check as tools/compare.py main(): every listed golden artifact is unmodified."""
    inventory = golden / "SHA256SUMS"
    if not inventory.is_file():
        raise AuthorityError(f"golden inventory {inventory} is missing")
    for line in inventory.read_text().splitlines():
        digest, name = line.split("  ", 1)
        f = golden / name
        if not f.is_file() or sha(f.read_bytes()) != digest:
            raise AuthorityError(f"modified or missing golden artifact: {name}")


class Authority:
    def __init__(self, kind: str, root: Path, golden: Path,
                 build_manifest: Path | None = None, guest_manifest: Path | None = None):
        self.kind, self.root, self.golden = kind, root, golden
        verify_golden_inventory(golden)
        self.coverage = json.loads((golden / "coverage.json").read_text())
        self.stages = {s["name"]: s for s in self.coverage["stages"]}
        cases = [json.loads(l) for l in (golden / "cases.jsonl").read_text().splitlines()]
        self.case_ids: dict[str, dict[int, str]] = {}
        for c in cases:
            self.case_ids.setdefault(c["stage"], {})[c["record"]] = c["id"]
        self.receipts: dict[str, dict] = {}
        self.build_manifest = build_manifest
        self.guest_manifest = guest_manifest
        self._provenance: dict[str, str] | None = None

    def provenance(self) -> dict[str, str]:
        """Build/guest manifests of the observed path and the frozen rate table, hashed here.

        The default location is the archive layout (<runtime>/<path>-provenance/{build,guest}.json
        next to <runtime>/<path>/); the build manifest must name this path as its backend so a
        manifest of another toolchain path cannot be substituted. Absent files fail closed.
        """
        if self._provenance is not None:
            return self._provenance
        path_name = self.root.name
        d = self.root.parent / f"{path_name}-provenance"
        build = self.build_manifest or d / "build.json"
        guest = self.guest_manifest or d / "guest.json"
        for label, f in (("build manifest", build), ("guest manifest", guest)):
            if not f.is_file():
                raise AuthorityError(f"{self.kind} {path_name}: missing {label} {f} (archive layout "
                                     f"<runtime>/{path_name}-provenance/ or --build-manifest/"
                                     f"--guest-manifest)")
        build_bytes, guest_bytes = build.read_bytes(), guest.read_bytes()
        try:
            build_doc, guest_doc = json.loads(build_bytes), json.loads(guest_bytes)
        except ValueError as e:
            raise AuthorityError(f"{self.kind} {path_name}: unreadable provenance manifest: {e}")
        if not isinstance(build_doc, dict) or build_doc.get("backend") != path_name:
            raise AuthorityError(f"{self.kind} {path_name}: build manifest {build} is for backend "
                                 f"{build_doc.get('backend') if isinstance(build_doc, dict) else None!r}")
        if not isinstance(guest_doc, dict) or not guest_doc:
            raise AuthorityError(f"{self.kind} {path_name}: guest manifest {guest} is not an object")
        self._provenance = {
            "path": path_name,
            "build_manifest": str(build), "build_manifest_sha256": sha(build_bytes),
            "guest_manifest": str(guest), "guest_manifest_sha256": sha(guest_bytes),
            "rates_json": str(self.golden / "rates.json"),
            "rates_sha256": sha((self.golden / "rates.json").read_bytes()),
        }
        return self._provenance

    def stage_bytes(self, stage: str) -> dict[str, bytes]:
        """Authority bytes for a stage; a2/a3 are refused unless the guest receipt validates."""
        s = self.stages[stage]
        golden_polin = (self.golden / s["input_master"]).read_bytes()
        golden_txnin = (self.golden / s["transactions"]).read_bytes()
        if self.kind == "a1":
            return {
                "polin": golden_polin,
                "txnin": golden_txnin,
                "polout": (self.golden / s["expected_master"]).read_bytes(),
                "resout": (self.golden / s["expected_results"]).read_bytes(),
            }
        d = self.root / stage
        data = {}
        for k in ("polin", "txnin", "polout", "resout"):
            f = d / f"{k}.bin"
            if not f.is_file():
                raise AuthorityError(f"{self.kind} {stage}: missing {f}")
            data[k] = f.read_bytes()
        if data["polin"] != golden_polin:
            raise AuthorityError(f"{self.kind} {stage}: polin.bin differs from the pinned golden "
                                 f"input master {s['input_master']}")
        if data["txnin"] != golden_txnin:
            raise AuthorityError(f"{self.kind} {stage}: txnin.bin differs from the pinned golden "
                                 f"transactions {s['transactions']}")
        receipt_path = d / "receipt.json"
        if not receipt_path.is_file():
            raise AuthorityError(f"{self.kind} {stage}: missing guest receipt {receipt_path}")
        try:
            receipt = json.loads(receipt_path.read_text())
        except ValueError as e:
            raise AuthorityError(f"{self.kind} {stage}: unreadable guest receipt: {e}") from None
        if not isinstance(receipt, dict):
            raise AuthorityError(f"{self.kind} {stage}: guest receipt is not an object")
        prov = self.provenance()
        hashes = {
            "polin_sha256": sha(golden_polin), "txnin_sha256": sha(golden_txnin),
            "polout_sha256": sha(data["polout"]), "resout_sha256": sha(data["resout"]),
            "build_manifest_sha256": prov["build_manifest_sha256"],
            "guest_manifest_sha256": prov["guest_manifest_sha256"],
            "rates_sha256": prov["rates_sha256"],
        }
        assert tuple(hashes) == GUEST_RECEIPT_HASH_KEYS
        missing = [k for k in GUEST_RECEIPT_HASH_KEYS if k not in receipt]
        if missing:
            raise AuthorityError(f"{self.kind} {stage}: guest receipt lacks {missing}")
        try:
            validate_receipt(receipt, hashes)
        except ValueError as e:
            raise AuthorityError(f"{self.kind} {stage}: guest receipt rejected: {e}")
        policies, txns = self.counts(stage)
        if len(data["polin"]) != policies * 128 or len(data["polout"]) != policies * 128:
            raise AuthorityError(f"{self.kind} {stage}: master bytes are not {policies} x 128")
        if len(data["txnin"]) != txns * 40 or len(data["resout"]) != txns * 96:
            raise AuthorityError(f"{self.kind} {stage}: transaction/result bytes are not "
                                 f"{txns} x 40/96")
        self.receipts[stage] = receipt
        return data

    def counts(self, stage: str) -> tuple[int, int]:
        s = self.stages[stage]
        return s["policies_count"], s["transactions_count"]


def validate_java_receipt(receipt: dict, expected: dict[str, object]) -> list[str]:
    errors = []
    if receipt.get("schema") != RECEIPT_SCHEMA:
        errors.append(f"receipt schema {receipt.get('schema')!r} != {RECEIPT_SCHEMA}")
    if receipt.get("record_schema") != RECORD_SCHEMA:
        errors.append("receipt record_schema mismatch")
    if receipt.get("outcome") != "completed" or receipt.get("return_code") != 0:
        errors.append(f"outcome/return_code: {receipt.get('outcome')}/{receipt.get('return_code')}")
    if receipt.get("publication_status") != "PUBLISHED":
        errors.append(f"publication_status {receipt.get('publication_status')}")
    for key, value in expected.items():
        if receipt.get(key) != value:
            errors.append(f"receipt {key}: {receipt.get(key)!r} != expected {value!r}")
    if receipt.get("results_count") != receipt.get("transactions_count"):
        errors.append("results_count != transactions_count")
    if receipt.get("typed_requests", 0) + receipt.get("raw_requests", 0) \
            != receipt.get("transactions_count"):
        errors.append("typed_requests + raw_requests != transactions_count")
    return errors


def manifest_for(stage: str, policies: int, txns: int, polin: bytes, txnin: bytes,
                 rates_sha: str) -> tuple[dict, bytes]:
    manifest = {
        "schema": MANIFEST_SCHEMA, "stage": stage,
        "policies_count": policies, "transactions_count": txns,
        "polin_sha256": sha(polin), "txnin_sha256": sha(txnin),
        "rates_sha256": rates_sha,
    }
    return manifest, (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()


# ---------------------------------------------------------------------------- runners


class Outcome:
    """What one runner produced for a stage; the driver compares and validates it."""

    def __init__(self) -> None:
        self.errors: list[str] = []
        self.request_errors: list[dict] = []
        self.polout = b""
        self.resout = b""
        self.requests: bytes | None = None
        self.receipt: dict | None = None
        self.typed = 0
        self.raw = 0
        self.first_result_mismatch: int | None = None


class BatchRunner:
    mode = "batch"

    def __init__(self, args) -> None:
        self.jar, self.work, self.source_commit = args.jar, args.work, args.source_commit

    def _java(self, *cli: str) -> subprocess.CompletedProcess[str]:
        cmd = [os.environ.get("JAVA", "java"), "-jar", str(self.jar), "batch", *cli]
        return subprocess.run(cmd, capture_output=True, text=True)

    def bootstrap(self, ns: str, gen: str, polin: bytes, manifest_bytes: bytes) -> str | None:
        d = self.work / "stages" / f"{ns}--root"
        d.mkdir(parents=True, exist_ok=True)
        (d / "polin.bin").write_bytes(polin)
        (d / "manifest.json").write_bytes(manifest_bytes)
        proc = self._java("bootstrap", "--store", str(self.work / "store"), "--namespace", ns,
                          "--generation", gen, "--polin", str(d / "polin.bin"),
                          "--manifest", str(d / "manifest.json"))
        return None if proc.returncode == 0 else \
            f"java exit {proc.returncode}: {proc.stderr.strip()[:2000]}"

    def run(self, ns: str, parent: str, gen: str, data: dict[str, bytes],
            manifest_bytes: bytes) -> Outcome:
        out = Outcome()
        d = self.work / "stages" / f"{ns}--{gen}"
        d.mkdir(parents=True, exist_ok=True)
        (d / "polin.bin").write_bytes(data["polin"])
        (d / "txnin.bin").write_bytes(data["txnin"])
        (d / "manifest.json").write_bytes(manifest_bytes)
        out_dir = d / "out"
        proc = self._java("run", "--store", str(self.work / "store"), "--namespace", ns,
                          "--parent", parent, "--generation", gen,
                          "--polin", str(d / "polin.bin"), "--txnin", str(d / "txnin.bin"),
                          "--manifest", str(d / "manifest.json"), "--out", str(out_dir),
                          "--source-commit", self.source_commit)
        if proc.returncode != 0:
            out.errors.append(f"java exit {proc.returncode}: {proc.stderr.strip()[:2000]}")
            return out
        out.polout = (out_dir / "polout.bin").read_bytes()
        out.resout = (out_dir / "resout.bin").read_bytes()
        out.receipt = json.loads((out_dir / "receipt.json").read_text())
        out.raw = len(data["txnin"]) // 40
        return out


class HttpError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:500]}")
        self.status, self.body = status, body


class HttpRunner:
    def __init__(self, args, mode: str) -> None:
        self.mode, self.base, self.work = mode, args.base_url.rstrip("/"), args.work
        self.source_commit = args.source_commit

    def _call(self, method: str, path: str, body: dict | None = None) -> tuple[int, bytes]:
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers={"Content-Type": "application/json",
                                              "Accept": "*/*"})
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            raise HttpError(e.code, e.read().decode("utf-8", "replace")) from None

    def _json(self, method: str, path: str, body: dict | None = None) -> dict:
        _, raw = self._call(method, path, body)
        return json.loads(raw)

    def bootstrap(self, ns: str, gen: str, polin: bytes, manifest_bytes: bytes) -> str | None:
        try:
            self._json("POST", f"/v1/namespaces/{ns}/import", {
                "generation": gen,
                "polinBase64": base64.b64encode(polin).decode(),
                "manifestBase64": base64.b64encode(manifest_bytes).decode()})
            return None
        except HttpError as e:
            return str(e)

    def run(self, ns: str, parent: str, gen: str, data: dict[str, bytes],
            manifest_bytes: bytes) -> Outcome:
        out = Outcome()
        base = f"/v1/namespaces/{ns}"
        try:
            # The expected manifest is pinned at creation; publish never accepts one.
            lease = self._json("POST", f"{base}/generations", {
                "parent": parent, "generation": gen,
                "polinBase64": base64.b64encode(data["polin"]).decode(),
                "manifestBase64": base64.b64encode(manifest_bytes).decode()})
        except HttpError as e:
            out.errors.append(f"begin: {e}")
            return out
        fence = lease["fence"]
        txnin = data["txnin"]
        if len(txnin) % 40:
            out.errors.append(f"authority TXNIN is not whole 40-byte records: {len(txnin)}")
            return out
        expected_results = data["resout"]
        first_result_mismatch = None
        for i in range(len(txnin) // 40):
            record = txnin[i * 40:(i + 1) * 40]
            view = typed_view(record) if self.mode == "http-json" else None
            try:
                if view is None:
                    resp = self._json("POST", f"{base}/generations/{gen}/requests:raw",
                                      {"fence": fence, "recordHex": record.hex()})
                else:
                    resp = self._json("POST", f"{base}/generations/{gen}/requests",
                                      {"fence": fence, "request": view})
            except HttpError as e:
                out.request_errors.append({"record": i, "typed": view is not None, "error": str(e),
                                           "request_hex": record.hex()})
                break  # the generation is now short; publication will report the count gap
            expected = expected_results[i * 96:(i + 1) * 96]
            errs = response_errors(resp, record, i, view, expected)
            if errs:
                out.request_errors.append({"record": i, "typed": view is not None, "errors": errs,
                                           "request_hex": record.hex(),
                                           "expected_result_hex": expected.hex(),
                                           "response": resp})
            if first_result_mismatch is None and \
                    (resp.get("resultHex") or "").lower() != expected.hex():
                first_result_mismatch = i
            if view is None:
                out.raw += 1
            else:
                out.typed += 1
        out.first_result_mismatch = first_result_mismatch
        try:
            out.receipt = self._json("POST", f"{base}/generations/{gen}/publish", {
                "fence": fence, "mode": self.mode})
        except HttpError as e:
            out.errors.append(f"publish: {e}")
            return out
        try:
            out.polout = self._call("GET", f"{base}/generations/{gen}/polout")[1]
            out.resout = self._call("GET", f"{base}/generations/{gen}/resout")[1]
            out.requests = self._call("GET", f"{base}/generations/{gen}/requests")[1]
            current = self._json("GET", f"{base}/current")
            if current.get("current") != gen:
                out.errors.append(f"current generation is {current.get('current')!r}, not {gen}")
        except HttpError as e:
            out.errors.append(f"published reads: {e}")
        return out


# ---------------------------------------------------------------------------- stage driver


def run_stage(args, runner, auth: Authority, jar_sha: str, rates_sha: str, ns: str, parent: str,
              stage: str) -> dict:
    gen = f"{ns}-{stage}"
    try:
        data = auth.stage_bytes(stage)
    except AuthorityError as e:
        return {"stage": stage, "namespace": ns, "generation": gen, "parent": parent,
                "authority_rejected": str(e), "errors": [str(e)], "request_errors": [],
                "mismatches": [], "receipt_errors": [], "typed_requests": 0, "raw_requests": 0,
                "expected_counts": dict(zip(("policies", "transactions"), auth.counts(stage))),
                "passed": False}
    policies, txns = auth.counts(stage)
    manifest, manifest_bytes = manifest_for(stage, policies, txns, data["polin"], data["txnin"],
                                            rates_sha)
    stage_dir = args.work / "stages" / f"{ns}--{gen}"
    stage_dir.mkdir(parents=True, exist_ok=True)
    (stage_dir / "manifest.json").write_bytes(manifest_bytes)
    started = time.time()
    out = runner.run(ns, parent, gen, data, manifest_bytes)
    result = {
        "stage": stage, "namespace": ns, "generation": gen, "parent": parent,
        "authority_input_sha256": {"polin": manifest["polin_sha256"],
                                   "txnin": manifest["txnin_sha256"]},
        "authority_expected_sha256": {"polout": sha(data["polout"]), "resout": sha(data["resout"])},
        "expected_counts": {"policies": policies, "transactions": txns},
        "seconds": round(time.time() - started, 3),
        "errors": list(out.errors), "request_errors": out.request_errors,
        "mismatches": [], "receipt_errors": [],
        "typed_requests": out.typed, "raw_requests": out.raw,
    }
    result["first_result_mismatch_record"] = out.first_result_mismatch
    if out.errors or out.receipt is None:
        result["passed"] = False
        return result
    (stage_dir / "polout.bin").write_bytes(out.polout)
    (stage_dir / "resout.bin").write_bytes(out.resout)
    (stage_dir / "receipt.json").write_text(json.dumps(out.receipt, indent=2, sort_keys=True) + "\n")
    result["observed_sha256"] = {"polout": sha(out.polout), "resout": sha(out.resout)}
    if out.requests is not None:
        result["observed_sha256"]["requests"] = sha(out.requests)
        if out.requests != data["txnin"]:
            result["errors"].append("published request bytes differ from the authority TXNIN")
    result["receipt_errors"] = validate_java_receipt(out.receipt, {
        "mode": runner.mode, "stage": stage, "namespace": ns, "generation": gen,
        "parent_generation": parent, "source_commit": args.source_commit,
        "build_identity": f"jar:sha256:{jar_sha}", "rate_table_sha256": rates_sha,
        "expected_manifest_sha256": sha(manifest_bytes),
        "polin_sha256": sha(data["polin"]), "txnin_sha256": sha(data["txnin"]),
        "polout_sha256": sha(out.polout), "resout_sha256": sha(out.resout),
        "policies_count": policies, "transactions_count": txns, "results_count": txns,
        "typed_requests": out.typed, "raw_requests": out.raw,
    })
    result["mismatches"] = differences(data["resout"], out.resout, "result",
                                       auth.case_ids.get(stage, {}))
    result["mismatches"] += differences(data["polout"], out.polout, "state")
    result["receipt"] = out.receipt
    if auth.kind != "a1":
        result["guest_receipt"] = auth.receipts[stage]
    result["passed"] = not result["errors"] and not result["mismatches"] \
        and not result["receipt_errors"] and not result["request_errors"]
    return result


def bootstrap(args, runner, auth: Authority, ns: str, stage: str, rates_sha: str) -> dict:
    gen = f"{ns}-root"
    try:
        data = auth.stage_bytes(stage)
    except AuthorityError as e:
        return {"namespace": ns, "generation": gen, "polin_sha256": None,
                "error": f"authority rejected: {e}"}
    policies, _ = auth.counts(stage)
    _, manifest_bytes = manifest_for(f"{stage}-root", policies, 0, data["polin"], b"",
                                     rates_sha)
    error = runner.bootstrap(ns, gen, data["polin"], manifest_bytes)
    return {"namespace": ns, "generation": gen, "polin_sha256": sha(data["polin"]),
            "error": error}


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--mode", choices=MODES, default="batch")
    p.add_argument("--authority", choices=["a1", "a2", "a3"], required=True)
    p.add_argument("--authority-dir", type=Path,
                   help="a2/a3: <runtime>/<path> directory holding <stage>/{polin,txnin,polout,"
                        "resout}.bin and receipt.json; <runtime>/<path>-provenance/{build,guest}"
                        ".json are the default provenance manifests")
    p.add_argument("--build-manifest", type=Path,
                   help="a2/a3: build manifest the guest receipts bind (default: archive layout)")
    p.add_argument("--guest-manifest", type=Path,
                   help="a2/a3: guest manifest the guest receipts bind (default: archive layout)")
    p.add_argument("--golden", type=Path, default=SAMPLE / "golden" / "v1",
                   help="golden dir for coverage.json/cases.jsonl/rates.json (record ids, counts)")
    p.add_argument("--jar", type=Path, required=True,
                   help="ledger-app fat jar (executed in batch mode; hashed for the receipt binding "
                        "in every mode, so pass the jar the HTTP server was started from)")
    p.add_argument("--base-url", default="http://127.0.0.1:8080",
                   help="http-* modes: base URL of the running ledger-app (PostgreSQL-backed)")
    p.add_argument("--namespace-prefix", default="",
                   help="prefix for the anchors/a namespaces so several runs can share one "
                        "database (a namespace has exactly one root)")
    p.add_argument("--work", type=Path, required=True, help="fresh working directory")
    p.add_argument("--report", type=Path, required=True, help="JSON report path")
    p.add_argument("--source-commit", required=True)
    p.add_argument("--label", default="", help="free-text label (e.g. evidence path name)")
    args = p.parse_args()
    if args.authority != "a1" and not args.authority_dir:
        p.error("--authority-dir is required for a2/a3")
    try:
        auth = Authority(args.authority, args.authority_dir or args.golden, args.golden,
                         args.build_manifest, args.guest_manifest)
        provenance = auth.provenance() if args.authority != "a1" else None
    except AuthorityError as e:
        raise SystemExit(f"FAIL: authority rejected before any Java code ran: {e}")
    args.work.mkdir(parents=True, exist_ok=True)
    jar_sha = sha(args.jar.read_bytes())
    rates_sha = rate_table_sha(args.golden / "rates.json")
    runner = BatchRunner(args) if args.mode == "batch" else HttpRunner(args, args.mode)
    report = {
        "schema": "insurance-java-parity-v1", "mode": args.mode,
        "authority": {"kind": args.authority, "label": args.label,
                      "dir": str(args.authority_dir or args.golden),
                      "golden_rates_json_sha256": sha((args.golden / "rates.json").read_bytes()),
                      "rate_table_canonical_sha256": rates_sha,
                      "provenance": provenance},
        "jar_sha256": jar_sha, "source_commit": args.source_commit,
        "base_url": args.base_url if args.mode != "batch" else None,
        "java_version": subprocess.run([os.environ.get("JAVA", "java"), "-version"],
                                       capture_output=True, text=True).stderr.strip(),
        "roots": [], "stages": [], "stopped_at": None,
    }
    for root, stages in ROOTS.items():
        ns = f"{args.namespace_prefix}{root}"
        boot = bootstrap(args, runner, auth, ns, stages[0], rates_sha)
        report["roots"].append(boot)
        if boot["error"]:
            report["stopped_at"] = f"{ns}:bootstrap"
            break
        parent = boot["generation"]
        for stage in stages:
            r = run_stage(args, runner, auth, jar_sha, rates_sha, ns, parent, stage)
            report["stages"].append(r)
            if not r["passed"]:
                report["stopped_at"] = f"{ns}:{stage}"
                break
            parent = r["generation"]
        if report["stopped_at"]:
            break
    expected = sum(len(v) for v in ROOTS.values())
    passed_stages = [s for s in report["stages"] if s["passed"]]
    report["stages_passed"] = len(passed_stages)
    report["stages_expected"] = expected
    report["results_compared"] = sum(s["expected_counts"]["transactions"] for s in passed_stages)
    report["typed_requests"] = sum(s["typed_requests"] for s in passed_stages)
    report["raw_requests"] = sum(s["raw_requests"] for s in passed_stages)
    report["passed"] = report["stages_passed"] == expected and not report["stopped_at"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    status = "PASS" if report["passed"] else "FAIL"
    coverage = (f", typed {report['typed_requests']}/{report['results_compared']} "
                f"(raw fallback {report['raw_requests']})" if args.mode == "http-json" else "")
    print(f"{status}: authority={args.authority} mode={args.mode} stages "
          f"{report['stages_passed']}/{expected}, results compared {report['results_compared']}"
          f"{coverage}; report {args.report}")
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
