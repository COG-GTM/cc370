# Record schemas (contract V001)

Host files contain consecutive fixed-length records without separators; guest
FB datasets group them into blocks. The records contain three
encodings that must never be converted as a unit: CP037 text, big-endian
two's-complement binary, and signed packed decimal. Monetary fields are
integer **cents**, scale 0, maximum 99,999,999,999 (`MAXAMT`). Dates are
positive `YYYYMMDD` values held as signed binary fullwords, not packed or text.
Reserved and tail bytes are binary zero and are validated, so a sender that
pads with CP037 blanks (`X'40'`) is rejected rather than silently accepted.

Offsets come from `copy/INSWORK.copy`; `tools/check_layout.py` fails the build
if the assembled DSECT and `tools/codec.py` ever disagree with this table.

## POLIN / POLOUT policy state — LRECL 128, `SREC`

| Off | Len | Field | Encoding | Meaning |
|----|----|----|----|----|
| 0 | 8 | `SID` | CP037 digits | Policy identifier, strictly increasing across the file |
| 8 | 4 | `SISSUE` | binary | Issue date `YYYYMMDD` |
| 12 | 4 | `SDATE` | binary | Last valuation date; interest accrues from here |
| 16 | 4 | `SSEQ` | binary | Accepted-transaction high-water mark; 0 = never transacted |
| 20 | 7 | `SFACE` | packed | Face amount, cents |
| 27 | 7 | `SCASH` | packed | Cash value, cents |
| 34 | 7 | `SLOAN` | packed | Outstanding loan, cents, `<= SCASH` |
| 41 | 7 | `SPAD` | binary zero | Alignment reserve before the 40-byte checkpoint |
| 48 | 40 | `SLAST` | copy of `TREC` | Last accepted request, the replay token |
| 88 | 40 | `STAIL` | binary zero | Reserved for future contract versions |

## TXNIN transaction — LRECL 40, `TREC`

| Off | Len | Field | Encoding | Meaning |
|----|----|----|----|----|
| 0 | 8 | `TID` | CP037 digits | Target policy |
| 8 | 4 | `TSEQ` | binary | Request sequence, must exceed `SSEQ` to be new |
| 12 | 4 | `TDATE` | binary | Effective date, must not precede `SDATE` |
| 16 | 1 | `TOP` | CP037 | `P` `W` `L` `R` `Q` `D` |
| 17 | 3 | `TPAD` | binary zero | Reserved |
| 20 | 7 | `TAMT` | packed | Amount in cents, zero for `Q`/`D` |
| 27 | 13 | `TTAIL` | binary zero | Reserved |

## RESOUT result — LRECL 96, `OREC`

| Off | Len | Field | Encoding | Meaning |
|----|----|----|----|----|
| 0 | 8 | `OID` | CP037 | Echo of `TID` |
| 8 | 4 | `OSEQ` | binary | Echo of `TSEQ` |
| 12 | 4 | `ODATE` | binary | Echo of `TDATE` |
| 16 | 4 | `OSTAT` | CP037 | Status, see below |
| 20 | 1 | `OOP` | CP037 | Echo of `TOP` |
| 21 | 3 | `OPAD` | binary zero | Reserved |
| 24 | 4 | `OAGE` | binary | Completed policy years at `TDATE` |
| 28 | 3 | `ORATE` | packed | Credit rate, basis points |
| 31 | 3 | `OFEE` | packed | Surrender-charge rate, basis points |
| 34 | 7 | `OCASH` | packed | Cash value after the transaction |
| 41 | 7 | `OSURR` | packed | Surrender value |
| 48 | 7 | `ODEATH` | packed | Death-claim quote |
| 55 | 7 | `OLOAN` | packed | Loan balance after the transaction |
| 62 | 7 | `OINT` | packed | Interest credited by this transaction |
| 69 | 7 | `OCHG` | packed | Surrender charge |
| 76 | 4 | `OVERS` | CP037 | Contract version, `V001` |
| 80 | 16 | `OTAIL` | binary zero | Reserved |

On a rejection, `OCASH`/`OLOAN` carry the unchanged stored values and the other
amounts are packed zero, so a consumer never sees an uninitialized field. A
`STAT` rejection cannot report balances at all: the state it would read is the
state that failed validation, so every amount stays zero. `INSBAT`'s missing-policy
path also returns zero balances because no stored policy was found.

PL7 holds 13 decimal digits and a sign nibble; PL3 holds five digits and a sign.
Accepted input signs are C (positive), D (negative), and F (unsigned positive).
Other sign nibbles are rejected even if supported by other S/370 applications.
Negative zero compares as zero and is permitted. Arithmetic result fields
use C for non-negative values. The retained `SLAST` bytes preserve the input
sign, including D-zero and F, because replay equality is byte-based.

`SSEQ`/`TSEQ` range up to 2,147,483,647; zero marks an initial policy, and new
requests require a positive sequence. There is no wraparound protocol.
Within `WORK`, SREC starts at 0, TREC at 128 and OREC at 168; these
three records occupy adjacent storage. Their `DS 0CL...` labels overlay the
field declarations. Scratch storage follows OREC, with doubleword alignment
before the eight-byte `WNUM` used by CVD.

## Statuses

| Status | Meaning | Rule |
|----|----|----|
| `OKAY` | Applied and committed | — |
| `STAT` | Stored policy failed validation | BR-004 |
| `NPOL` | No such policy in this generation | BR-011 |
| `ORDR` | Sequence below the high-water mark, or non-positive | BR-005 |
| `DUPL` | Byte-identical replay of the last accepted request | BR-005 |
| `CNFL` | Same sequence, different request bytes | BR-005 |
| `FORM` | Reserved bytes not binary zero | BR-013 |
| `PACK` | `TAMT` is not valid signed packed decimal | BR-001 |
| `NEGA` | Negative amount | BR-013 |
| `OVER` | Amount or an intermediate exceeds `MAXAMT` | BR-014 |
| `DATE` | Invalid calendar date or backdated effective date | BR-002, BR-006 |
| `TYPE` | Unknown operation code | BR-008 |
| `AMNT` | `Q`/`D` carried a non-zero amount | BR-008 |
| `FUND` | Result would be negative cash/loan, or loan above cash | BR-013 |
