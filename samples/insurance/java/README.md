# Java modernization of the synthetic insurance assembler sample

A Java 21 / Spring Boot re-implementation of the seven-module S/370 sample in
`samples/insurance` (INSBAT, INSCALC, INSVAL, INSPACK, INSDATE, INSRATE,
INSSMOK), built to reproduce the legacy **byte contract** exactly and to be
proven against the existing harness, goldens and archived MVS output rather
than against a shared calculation library.

This is a bounded synthetic demonstration. It makes no claim about any real
insurer's rules, data, z/OS, HLASM, production scale or power-loss behavior.

## Layout

| Module | Contents | Framework |
|---|---|---|
| `legacy-codec` | CP037, big-endian fullword, packed decimal (C/D/F signs, negative zero preserved), raw byte-backed 128/40/96-byte records (`PolicyRecord`, `TransactionRecord`, `ResultRecord`) | none |
| `contract-v001` | `ContractV001`: the V001 business contract — validation precedence, half-up interest, quote/commit operations, replay/order/conflict, exact PL7/PL3 width checks, 1900-2099 calendar, frozen rate table | none |
| `ledger-application` | `PolicyLedgerService`, `BatchRunner` (INSBAT semantics), `ExpectedManifest` (independent counts + input hashes), `Receipt`, `GenerationStore` + `FileGenerationStore` (pending/published/discarded generations), `BatchCli` | Jackson only |
| `ledger-app` | Single Spring Boot deployable; `java -jar ledger-app.jar batch ...` dispatches to the CLI without starting the web container | Spring Boot |
| `tools/parity_java.py` | Parity driver: runs the JAR over anchors/A/A-replay/B/B-replay against an authority, validates receipts independently, reuses the unchanged `../tools/compare.py` for field-level diffs | Python 3 |

Legacy source, macros, layouts, goldens, tests and `../tools/compare.py` are
unchanged; the Java tree is additive.

## Build and check

```sh
cd samples/insurance/java
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # any JDK 21
export MVNW_REPOURL=https://maven-central.storage-download.googleapis.com/maven2  # if repo.maven.apache.org is unreachable
./mvnw verify          # compile (-Werror), unit tests, spotless:check, checkstyle:check
./mvnw package         # -> ledger-app/target/ledger-app-0.1.0-SNAPSHOT.jar
```

## Batch adapter

```
java -jar ledger-app/target/ledger-app-0.1.0-SNAPSHOT.jar batch <command> ...
  bootstrap --store DIR --namespace NS --generation GEN --polin F --manifest F
  run       --store DIR --namespace NS --parent GEN --generation GEN
            --polin F --txnin F --manifest F --out DIR [--source-commit SHA]
  inbat     --polin F --txnin F --manifest F --out DIR
  verify    --store DIR
```

`run` pins the successor to a published parent whose bytes must equal the
supplied POLIN, applies every TXNIN record in physical order, validates the
outputs against the separately supplied expected manifest (counts and input
SHA-256s), publishes with an expected-parent compare-and-swap of the
namespace's current pointer, and writes `polout.bin`, `resout.bin` and
`receipt.json` to `--out`. Exit codes: 0 ok, 2 manifest/input rejection,
3 lifecycle/publication failure, 4 usage.

Legacy INSBAT semantics preserved: invalid master state -> RC 12 and no
output; genuine empty POLIN -> every transaction `NPOL`, zero POLOUT; genuine
empty TXNIN -> master copied unchanged, zero RESOUT. "Genuine" is decided by
the manifest, not by the delivered file, so aligned truncation (lost whole
records) and partial records are both rejected before anything is published.

## Parity (FAST regression)

Authorities are kept distinct:

- **A1** generated goldens: `../golden/v1`
- **A2** archived observed MVS output: `../evidence/28790e2/runtime/<path>`
- **A3** fresh Hercules/MVS output: only if actually executed and recorded

```sh
cd samples/insurance/java
JAR=ledger-app/target/ledger-app-0.1.0-SNAPSHOT.jar
SHA=$(git rev-parse HEAD)
python3 tools/parity_java.py --authority a1 --jar $JAR --source-commit $SHA \
    --work /path/to/fresh/a1 --report /path/to/a1-report.json
for p in ifox-iewl as370-iewl as370-ld370; do
  python3 tools/parity_java.py --authority a2 --label $p \
      --authority-dir ../evidence/28790e2/runtime/$p --jar $JAR --source-commit $SHA \
      --work /path/to/fresh/a2-$p --report /path/to/a2-$p-report.json
done
```

The driver uses independent roots for `anchors` and the `a -> a-replay -> b
-> b-replay` chain, seeds each Java generation from the Java-produced,
validated predecessor, recomputes every receipt hash from the actual bytes,
compares POLOUT and RESOUT byte-for-byte including padding, tails, sign
nibbles and `SLAST`, reports each mismatch with record number, ID, field,
offset, raw bytes and decoded values, and stops at the first failing stage.

Current phase-1 results (`evidence/fast/`): A1 and all three A2 paths pass
5/5 stages, 8,704 results each, with successor masters identical.

## Status and limitations

- Phase 1 (this state): codec, V001 core, batch adapter, file generation
  store, receipts, parity driver, A1/A2 batch FAST parity.
- Pending on this PR: PostgreSQL store (Flyway, Spring Data JDBC, published-row
  INSERT/UPDATE/DELETE guards), stateful raw and hybrid typed/raw HTTP with
  `http-gen`/`http-json` FAST parity, Java-only crash/fence/CAS/retry tests,
  and A3 targeted fresh guest cases (interest tie, reachable quotient OVER).
- Agreement with the source-derived oracle and goldens is implementation
  evidence, not independent business-intent validation.
- The file store does not claim power-loss durability (no fsync/cut-power
  tests); publication is two distinct renames, and restart fails closed on
  any inconsistency it can detect.
- Per-routine assembler integration acceptance is a separate workstream and
  remains pending until reviewed fixtures and observations are supplied.
