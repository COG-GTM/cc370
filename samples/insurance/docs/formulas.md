# Formula catalog, business-rule inventory and worked examples

Every rule here is **synthetic**. The values were chosen to exercise rounding,
boundaries and ordering, not to model any real product, and no part of this is
actuarial advice or a description of any insurer's book of business.

## Rule inventory, with source

| ID | Rule | Source |
|----|----|----|
| BR-001 | Each state or transaction amount is digit- and sign-checked before arithmetic consumes it | `src/INSPACK.asm` |
| BR-002 | Dates are Gregorian 1900-01-01..2099-12-31; ordinal day 0 is 1900-01-01 | `src/INSDATE.asm` |
| BR-003 | Credit and surrender-charge rates come from an effective-date table, latest row at or below `TDATE`; duration ≥ 10 waives the charge | `src/INSRATE.asm` |
| BR-004 | Stored state is validated before it can become a calculation candidate | `src/INSVAL.asm` |
| BR-005 | `SSEQ` is a high-water mark; exact replay is `DUPL`, altered replay `CNFL`, older `ORDR` | `src/INSCALC.asm` |
| BR-006 | Effective dates never move backwards; a Feb-29 issue anniversaries on Mar 1 in common years | `src/INSCALC.asm`, `src/INSDATE.asm` |
| BR-007 | Interest is simple, actual/365, half-up, at the transaction date's rate | `src/INSCALC.asm` |
| BR-008 | Operations dispatch indirectly through an address table; quotes must carry a zero amount | `src/INSCALC.asm` |
| BR-009 | The surrender charge is rounded half-up before the loan is deducted; the result floors at zero | `src/INSCALC.asm` |
| BR-010 | Death claim is `max(face, cash) - loan`, floored at zero | `src/INSCALC.asm` |
| BR-011 | At most 512 policies per generation, in strictly increasing ID order; an unknown ID is `NPOL` | `src/INSBAT.asm` |
| BR-012 | Physical input order is the business order; nothing is sorted | `src/INSBAT.asm` |
| BR-013 | Reserved bytes must be binary zero, amounts non-negative, and cash/loan non-negative with `loan <= cash` | `src/INSVAL.asm`, `src/INSCALC.asm` |
| BR-014 | `MAXAMT` = 99,999,999,999 cents bounds monetary inputs and balances; larger multiplication products use PL12 scratch | `src/INSCALC.asm`, `src/INSVAL.asm` |

## Rate table (synthetic)

| Effective from | Credit bps | Surrender charge bps |
|----|----|----|
| 1900-01-01 | 125 | 700 |
| 2000-01-01 | 175 | 600 |
| 2020-01-01 | 225 | 500 |
| 2024-01-01 | 300 | 400 |
| 2025-01-01 | 325 | 350 |

## Contract V001, in order

```text
days      = ordinal(TDATE) - ordinal(SDATE)                   # BR-002, >= 0
rate, fee = table row with the greatest effective date <= TDATE  # BR-003
age       = completed policy years between SISSUE and TDATE      # BR-006
fee       = 0 if age >= 10                                       # BR-003
interest  = round_half_up(SCASH * rate / 10000 * days / 365)     # BR-007
cash      = SCASH + interest
cash/loan = operation applied to cash or loan                    # BR-008
charge    = round_half_up(cash * fee / 10000)                    # BR-009
surrender = max(0, cash - charge - loan)                         # BR-009
death     = max(0, max(SFACE, cash) - loan)                      # BR-010
```

Half-up rounding of non-negative values uses integer arithmetic: the assembler
adds half the divisor before `DP` (`AP WPROD,=PL5'1825000'` then
`DP WPROD,=PL5'3650000'`). The divisor is 365 × 10000 = 3,650,000.
The host Decimal oracle is checked against independent Fraction arithmetic;
assembler execution still requires guest validation. Interest accrues on the stored
cash value before the operation is applied, so a premium earns nothing on the
day it is paid, and a withdrawal still earns interest for the days it was
invested. Quotes are not read-only: `Q` and `D` credit interest and advance
`SDATE`/`SSEQ` exactly like a money movement, which is the kind of hidden state
effect this sample exists to demonstrate.

The transaction date's rate applies to the entire interval, including intervals
that cross table changes. There is no daily compounding within an interval and
no split at a rate boundary. Accepted intervening transactions capitalize
interest, so adding a quote can change later amounts through both timing and
rounding. Policy age counts anniversaries, not elapsed days divided by 365.

| Operation | Effect after interest |
|----|----|
| P | Add amount to cash |
| W | Subtract amount from cash; reject if loan would exceed remaining cash |
| L | Add amount to loan; cash is unchanged; require loan <= cash |
| R | Subtract amount from loan; cash is unchanged; reject over-repayment |
| Q | Calculate cash/surrender quote; require zero amount |
| D | Calculate death quote; require zero amount; do not settle or close policy |

Loans have no separate interest accrual in V001, and disbursement/payment
accounting is outside this calculation slice. Premiums do not change face value.
Zero monetary transactions are permitted. Tax, mortality, product-specific
guarantees and real policyholder attributes are not modeled.

PL12 scratch holds 23 digits. Valid cash (11 digits), rate (at most 325) and
day span (at most 73,048) produce a product below 2.375e18, within that field.
The seven-byte quotient has 13 digits, enough to hold the largest interest
before the business maximum is checked. Balance addition/subtraction may exceed
the business maximum but remains within PL7 capacity; it returns `OVER`/`FUND`.

Validation precedence is state, identity, sequence/replay, reserved bytes,
packed syntax, sign, amount bound, date, accrued-balance bound, operation,
funding, and final balance bound. A request violating several rules reports
the first one encountered; reordering checks can change observable statuses.

## Worked example (anchor A001, embedded in `INSSMOK`; guest run pending)

Policy `00000001`, issued 2024-01-01, face 1,000,000, cash 100,000, loan 0.
Request: sequence 1, date 2025-01-01, `P` (premium) 10,000.

```text
days      = 366                       # 2024 is a leap year
rate, fee = 325, 350                  # the 2025-01-01 row, not 2024's 300/400
age       = 1
interest  = round_half_up(100000 * 0.0325 * 366 / 365)
          = round_half_up(3258.9041...) = 3259
cash      = 100000 + 3259 + 10000 = 113259
charge    = round_half_up(113259 * 0.035) = round_half_up(3964.065) = 3964
surrender = 113259 - 3964 - 0 = 109295
death     = max(1000000, 113259) - 0 = 1000000
```

Resubmitting those same 40 bytes gives `DUPL` and changes nothing. A `W` for
9,999,999 at sequence 2 gives `FUND` with cash still 113,259 and `SSEQ` still 1.
`X'FF'` in `TAMT` gives `PACK` before any decimal instruction executes.

## Anchors

`tests/anchors.json` holds ten hand-worked cases, including the Feb-29 issue
that anniversaries on Mar 1 (A003/A004), the 1900 non-leap-year rejection
(A008), the year-2000 leap day (A009), the duration-10 charge waiver (A005) and
the loan-dominated death quote (A010). They are the only expectations in this
sample written by hand rather than computed, and `tools/corpus.py` reads them
rather than producing them.
