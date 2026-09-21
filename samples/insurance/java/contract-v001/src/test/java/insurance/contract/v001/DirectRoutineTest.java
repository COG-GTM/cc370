package insurance.contract.v001;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Direct-call surfaces of INSDATE and INSRATE that INSCALC never reaches with unvalidated input.
 */
class DirectRoutineTest {
  @Test
  void yearIsSplitForEveryPositiveInputOnly() {
    assertEquals(OptionalInt.of(2100), LegacyCalendar.yearPart(21000101));
    assertEquals(OptionalInt.of(214748), LegacyCalendar.yearPart(Integer.MAX_VALUE));
    assertEquals(OptionalInt.of(0), LegacyCalendar.yearPart(1));
    assertEquals(OptionalInt.empty(), LegacyCalendar.yearPart(0));
    assertEquals(OptionalInt.empty(), LegacyCalendar.yearPart(-1));
    assertTrue(LegacyCalendar.parts(21000101).isEmpty());
    assertTrue(LegacyCalendar.parts(20991231).isPresent());
  }

  @Test
  void directRateWritesNothingBelowTheFirstRowExceptTheAgeWaiver() {
    RateTable t = RateTable.FROZEN;
    assertEquals(
        new RateTable.Direct(OptionalInt.empty(), OptionalInt.empty()), t.direct(18991231, 0));
    assertEquals(new RateTable.Direct(OptionalInt.empty(), OptionalInt.empty()), t.direct(0, 0));
    assertEquals(new RateTable.Direct(OptionalInt.empty(), OptionalInt.empty()), t.direct(-1, 9));
    assertEquals(
        new RateTable.Direct(OptionalInt.empty(), OptionalInt.of(0)), t.direct(18991231, 10));
    assertEquals(
        new RateTable.Direct(OptionalInt.of(125), OptionalInt.of(700)), t.direct(19000101, 0));
    assertEquals(
        new RateTable.Direct(OptionalInt.of(325), OptionalInt.of(350)), t.direct(99999999, 0));
    assertEquals(
        new RateTable.Direct(OptionalInt.of(225), OptionalInt.of(500)), t.direct(20200101, -1));
    assertEquals(
        new RateTable.Direct(OptionalInt.of(225), OptionalInt.of(0)),
        t.direct(20200101, Integer.MAX_VALUE));
    assertFalse(t.direct(20200101, 9).feeBps().getAsInt() == 0);
  }

  @Test
  void lookupAgreesWithDirectOnEveryValidatedDate() {
    RateTable t = RateTable.FROZEN;
    for (int date :
        new int[] {
          19000101, 19991231, 20000101, 20191231, 20200101, 20231231, 20240101, 20241231, 20250101,
          20991231
        }) {
      for (int age : new int[] {0, 9, 10, 99}) {
        RateTable.Row row = t.lookup(date, age);
        RateTable.Direct d = t.direct(date, age);
        assertEquals(d.rateBps().getAsInt(), row.rateBps());
        assertEquals(d.feeBps().getAsInt(), row.feeBps());
      }
    }
  }
}
