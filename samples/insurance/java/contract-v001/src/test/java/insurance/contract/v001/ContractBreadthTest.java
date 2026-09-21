package insurance.contract.v001;

import static insurance.contract.v001.Fixtures.fresh;
import static insurance.contract.v001.Fixtures.txn;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import insurance.legacy.codec.Layout.Transaction;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.TransactionRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Breadth promised by the acceptance plan (T-01 rounding control, T-03 every age on both sides of
 * the anniversary, T-06 every packed digit position, T-07 every replay-request offset).
 */
class ContractBreadthTest {
  private final ContractV001 contract = ContractV001.frozen();

  // ------------------------------------------------------------------ T-01 rounding control

  private static long interestWith(RoundingMode mode, long cash, long rateBps, long days) {
    return BigDecimal.valueOf(cash)
        .multiply(BigDecimal.valueOf(rateBps))
        .multiply(BigDecimal.valueOf(days))
        .divide(BigDecimal.valueOf(3_650_000L), 0, mode)
        .longValueExact();
  }

  /**
   * Negative control: on the exact tie (73,000 x 325 x 1 day = 6.5) HALF_EVEN and DOWN both yield
   * 6, so a wrong-rounding implementation is distinguishable from the frozen half-up 7. On a
   * non-tie all three agree, which is why only the tie is a discriminating check.
   */
  @Test
  void wrongRoundingIsDetectedOnTheTieOnly() {
    PolicyRecord m = fresh("00000002", 20250101, 0, 73_000, 0);
    Evaluation tie = contract.evaluate(m, txn("00000002", 1, 20250102, 'Q', 0));
    assertEquals(Status.OKAY, tie.status());
    assertEquals(7, tie.result().interest());
    assertEquals(7, interestWith(RoundingMode.HALF_UP, 73_000, 325, 1));
    assertEquals(6, interestWith(RoundingMode.HALF_EVEN, 73_000, 325, 1));
    assertEquals(6, interestWith(RoundingMode.DOWN, 73_000, 325, 1));
    assertNotEquals(tie.result().interest(), interestWith(RoundingMode.HALF_EVEN, 73_000, 325, 1));
    assertNotEquals(tie.result().interest(), interestWith(RoundingMode.DOWN, 73_000, 325, 1));

    PolicyRecord m2 = fresh("00000002", 20250101, 0, 73_001, 0);
    Evaluation nonTie = contract.evaluate(m2, txn("00000002", 1, 20250102, 'Q', 0));
    assertEquals(7, nonTie.result().interest());
    assertEquals(7, interestWith(RoundingMode.HALF_EVEN, 73_001, 325, 1));
  }

  // ------------------------------------------------------------------ T-03 ages 0..99

  /**
   * Issue 1925-06-15; for every age 0..99 the anniversary day itself and the day after report the
   * age, the day before reports age-1 (age 0's day before is pre-issue: DATE). The surrender fee is
   * waived from age 10 on, the rate is the table row in force on the transaction date.
   */
  @Test
  void everyAgeOnBothSidesOfTheAnniversary() {
    PolicyRecord m = fresh("00000003", 19250615, 0, 1_000, 0);
    for (int age = 0; age < 100; age++) {
      int year = 1925 + age;
      int anniversary = year * 10_000 + 615;
      int dayBefore = year * 10_000 + 614;
      int dayAfter = year * 10_000 + 616;
      Evaluation on = contract.evaluate(m, txn("00000003", 1, anniversary, 'Q', 0));
      Evaluation after = contract.evaluate(m, txn("00000003", 1, dayAfter, 'Q', 0));
      assertEquals(Status.OKAY, on.status(), "age " + age);
      assertEquals(age, on.result().age(), "on anniversary " + anniversary);
      assertEquals(age, after.result().age(), "after anniversary " + dayAfter);
      Evaluation before = contract.evaluate(m, txn("00000003", 1, dayBefore, 'Q', 0));
      if (age == 0) {
        assertEquals(Status.DATE, before.status(), "pre-issue");
      } else {
        assertEquals(age - 1, before.result().age(), "before anniversary " + dayBefore);
        RateTable.Row rowBefore = RateTable.FROZEN.lookup(dayBefore, age - 1);
        assertEquals(rowBefore.rateBps(), before.result().rate());
        assertEquals(rowBefore.feeBps(), before.result().fee());
        assertEquals(
            age - 1 >= RateTable.FEE_WAIVER_AGE ? 0 : rowBefore.feeBps(), before.result().fee());
      }
      RateTable.Row row = RateTable.FROZEN.lookup(anniversary, age);
      assertEquals(row.rateBps(), on.result().rate());
      assertEquals(age >= RateTable.FEE_WAIVER_AGE ? 0 : row.feeBps(), on.result().fee());
    }
  }

  // ------------------------------------------------------------------ T-06 13 packed digits

  private static TransactionRecord amountNibbles(TransactionRecord t, int[] nibbles) {
    byte[] raw = t.bytes();
    for (int i = 0; i < 7; i++) {
      raw[Transaction.AMOUNT + i] = (byte) ((nibbles[2 * i] << 4) | nibbles[2 * i + 1]);
    }
    return new TransactionRecord(raw);
  }

  /**
   * TAMT is PL7: digits d1..d13 in the 13 leading nibbles, sign in the last. Every digit position
   * is decoded at its own weight (10^(13-i)); an invalid nibble (A-F) at any digit position is
   * PACK; an invalid sign nibble (0-9) is PACK.
   */
  @Test
  void everyPackedDigitPositionIsDecodedAndValidated() {
    PolicyRecord m = fresh("00000006", 20240101, 0, 500, 0);
    TransactionRecord base = txn("00000006", 1, 20240102, 'P', 0);
    for (int pos = 1; pos <= 13; pos++) {
      int[] nibbles = new int[14];
      nibbles[13] = 0xC;
      nibbles[pos - 1] = 1;
      Evaluation e = contract.evaluate(m, amountNibbles(base, nibbles));
      long weight = (long) Math.pow(10, 13 - pos);
      if (weight > ContractV001.MAX_AMOUNT) {
        assertEquals(Status.OVER, e.status(), "digit " + pos);
      } else {
        assertEquals(Status.OKAY, e.status(), "digit " + pos);
        assertEquals(500 + weight, e.result().cash(), "digit " + pos);
      }
      for (int bad = 0xA; bad <= 0xF; bad++) {
        int[] invalid = new int[14];
        invalid[13] = 0xC;
        invalid[pos - 1] = bad;
        assertEquals(
            Status.PACK,
            contract.evaluate(m, amountNibbles(base, invalid)).status(),
            "digit " + pos + " nibble " + Integer.toHexString(bad));
      }
    }
    for (int sign = 0; sign <= 9; sign++) {
      int[] invalid = new int[14];
      invalid[13] = sign;
      assertEquals(Status.PACK, contract.evaluate(m, amountNibbles(base, invalid)).status());
    }
    for (int sign : new int[] {0xA, 0xB, 0xE}) {
      int[] other = new int[14];
      other[13] = sign;
      assertEquals(Status.PACK, contract.evaluate(m, amountNibbles(base, other)).status());
    }
  }

  // ------------------------------------------------------------------ T-07 40 replay offsets

  private static String region(int offset) {
    if (offset < Transaction.SEQ) {
      return "TID";
    } else if (offset < Transaction.DATE) {
      return "TSEQ";
    } else if (offset < Transaction.OP) {
      return "TDATE";
    } else if (offset < Transaction.PAD) {
      return "TOP";
    } else if (offset < Transaction.AMOUNT) {
      return "TPAD";
    } else if (offset < Transaction.TAIL) {
      return "TAMT";
    }
    return "TTAIL";
  }

  /**
   * After one accepted request (seq 3) the exact replay is DUPL. Flipping the low bit at each of
   * the 40 offsets gives the first-failure outcome dictated by the precedence: a different id is
   * NPOL (no such policy in a single-master evaluation); a different sequence is ORDR when lower
   * (offset 11: 3 -> 2) and a fresh OKAY when higher (offsets 8..10); any other byte differing
   * under the same sequence is CNFL, before FORM/PACK could be reached. The master is unchanged
   * except for the accepted higher-sequence cases.
   */
  @Test
  void everyReplayOffsetHasItsFirstFailureOutcome() {
    PolicyRecord m = fresh("00000004", 20240101, 0, 5_000, 0);
    TransactionRecord first = txn("00000004", 3, 20240102, 'P', 1_000);
    Evaluation ok = contract.evaluate(m, first);
    assertEquals(Status.OKAY, ok.status());
    PolicyRecord after = ok.master();
    assertEquals(Status.DUPL, contract.evaluate(after, first).status());

    Map<Integer, Status> outcomes = new LinkedHashMap<>();
    Map<Status, Integer> histogram = new EnumMap<>(Status.class);
    for (int offset = 0; offset < TransactionRecord.LENGTH; offset++) {
      byte[] raw = first.bytes();
      raw[offset] ^= 0x01;
      Evaluation e = contract.evaluate(after, new TransactionRecord(raw));
      Status expected =
          switch (region(offset)) {
            case "TID" -> Status.NPOL;
            case "TSEQ" -> offset == 11 ? Status.ORDR : Status.OKAY;
            default -> Status.CNFL;
          };
      assertEquals(expected, e.status(), "offset " + offset + " (" + region(offset) + ")");
      if (expected == Status.OKAY) {
        assertArrayEquals(raw, e.master().last());
      } else {
        assertArrayEquals(after.bytes(), e.master().bytes(), "offset " + offset);
      }
      outcomes.put(offset, e.status());
      histogram.merge(e.status(), 1, Integer::sum);
    }
    assertEquals(40, outcomes.size());
    assertEquals(8, histogram.get(Status.NPOL));
    assertEquals(3, histogram.get(Status.OKAY));
    assertEquals(1, histogram.get(Status.ORDR));
    assertEquals(28, histogram.get(Status.CNFL));

    // high bit: a negative sequence is ORDR (offset 8), 0x83 = 131 is a fresh OKAY (offset 11)
    for (int offset = Transaction.SEQ; offset < Transaction.DATE; offset++) {
      byte[] raw = first.bytes();
      raw[offset] ^= (byte) 0x80;
      Status s = contract.evaluate(after, new TransactionRecord(raw)).status();
      assertEquals(offset == Transaction.SEQ ? Status.ORDR : Status.OKAY, s, "offset " + offset);
    }
  }
}
