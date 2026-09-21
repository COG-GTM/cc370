# Archived observed MVS evidence (A2) for baseline 28790e2

Subset of the reviewed evidence archive `insurance-evidence-28790e2.tar.gz`
(SHA-256 `fd0c8934bbc606e3ad9b0e965b44c81b6ea6c014987cfb86a57370108c2f1993`)
kept in-tree so the Java parity driver can be run against the *observed*
guest outputs without the full 70 MB archive.

Included verbatim from the archive:

- `FACTS.md`, `VERIFICATION.json`, `SHA256SUMS` (top-level provenance; every
  file below is listed in `SHA256SUMS` and verifies with
  `sha256sum -c --ignore-missing SHA256SUMS`).
- `runtime/<path>/<stage>/{polin,txnin,polout,resout}.bin` — the exact host
  input bytes sent to MVS and the exact output bytes fetched back for each of
  the three executable paths (`ifox-iewl`, `as370-iewl`, `as370-ld370`) and
  five stages (`anchors`, `a`, `a-replay`, `b`, `b-replay`).
- `runtime/<path>/<stage>/{generation,receipt,comparison}.json` — the original
  guest-run receipt and the legacy comparator's verdict for that stage.
- `runtime/<path>-provenance/{build,guest}.json` — the build and guest
  manifests whose SHA-256 the receipts carry as `build_manifest_sha256` /
  `guest_manifest_sha256`, so a consumer can recompute them independently
  instead of trusting the receipt's own values.

Not included: JCL/spool logs, AWS tapes, transport artifacts, decks, recovery
and control runs, screenshots. Those remain in the archive.

This directory is *observed* evidence (authority A2). It is distinct from the
generated goldens in `../../golden/v1` (A1) and from any fresh Hercules run
(A3). Nothing here was produced by the Java implementation.
