# Module and dependency map

These edges are `V`-constant references in the source. Host ESD/CESD inspection
confirms all module external symbols and linked sections, including the QSAM
driver assembled against the supplied TK5 SYS1.MACLIB export.

```text
INSBAT  (entry, QSAM)  POLIN -> TXNIN -> POLOUT + RESOUT
  |
  +-- INSVAL   (per master record, before it becomes a candidate)
  |     +-- INSPACK   (amount nibble/sign validation)
  |     +-- INSDATE   (issue and valuation dates)
  |
  +-- INSCALC  (per transaction)
        +-- INSVAL   (revalidates the candidate; INSVAL itself calls INSPACK/INSDATE)
        +-- INSPACK  (transaction amount)
        +-- INSDATE  (effective date, ordinal days, age components)
        +-- INSRATE  (effective-date rate lookup, duration waiver)

INSSMOK (entry, no OS macros) -- INSCALC ...   # hand-worked assertions, RC 0 or 12
```

## Calling convention

R13 points at the caller's 18-fullword save area and is chained forward by the
`INSENT` macro; `INSRET` unchains it. Calls are `L 15,=V(NAME)` then
`BALR 14,15`. R1 carries the address of the shared `WORK` DSECT, with one
deliberate exception: `INSPACK` takes the address of seven packed bytes, so a
caller that passes the work area gets a diagnosis of whatever happens to sit at
offset 0. R15 is the return code; every other register is restored. Results are
returned in the work area, not in registers — `INSDATE` writes `WORD`, `WYEAR`,
`WMD` and `WVALID`, and `INSRATE` writes `WRATE` and `WFEE`, so call order is
load-bearing. `INSCALC` calls `INSDATE` three times with different inputs and
saves the intermediate results into `WOLDORD`/`WISSYR`/`WISSMD` between calls.

`INSENT`/`INSRET` (`copy/`) are application macros independent of OS
`SAVE`/`RETURN` macros. `INSWORK.copy` is the shared DSECT: the state,
transaction and result records are adjacent in one contiguous area, so `INSCALC` can
move `TREC` into `SLAST` as one 40-byte `MVC`. `WBEFORE` holds the pre-image
that a failed transaction is rolled back from.

`INSCALC` dispatches on `TOP` through `OPTABLE`, eight bytes per entry
(`CL1` code, three reserved bytes, `A(routine)`), loading the address and
branching with `BR 15`. The table entries are relocatable, so the operation set
can be traced through the table and its relocation records.

Load modules are non-reentrant, non-reusable, unauthorized, 24-bit.
