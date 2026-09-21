package insurance.ledger.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import insurance.ledger.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutineCliTest {
  private static final String SREC =
      "f0f0f0f0f0f0f0f101343aa501343aa5000000000000010000000c0000000500000c0000000000000c"
          + "0".repeat(2 * (128 - 41));
  private static final String TREC =
      "f0f0f0f0f0f0f0f100000001013461b5d70000000000000100000c" + "0".repeat(2 * 13);

  private static RoutineCli.Case of(String id, String routine) {
    return new RoutineCli.Case(id, routine, null, null, null, null, null, null);
  }

  @Test
  void inspackReportsPackedValidityOnly() {
    var ok =
        RoutineCli.call(
            new RoutineCli.Case("p", "INSPACK", "0000000000000c", null, null, null, null, null));
    var bad =
        RoutineCli.call(
            new RoutineCli.Case("p", "INSPACK", "0000000000000a", null, null, null, null, null));
    assertTrue(ok.valid());
    assertFalse(bad.valid());
    assertNull(ok.year());
    assertNull(ok.status());
  }

  @Test
  void insdateWritesYearForPositiveInvalidDatesAndPartsOnlyWhenValid() {
    var valid =
        RoutineCli.call(
            new RoutineCli.Case("d", "INSDATE", null, 19000101, null, null, null, null));
    assertTrue(valid.valid());
    assertEquals(1900, valid.year());
    assertEquals(101, valid.monthDay());
    assertEquals(0, valid.ordinal());
    var badMonth =
        RoutineCli.call(
            new RoutineCli.Case("d", "INSDATE", null, 20231301, null, null, null, null));
    assertFalse(badMonth.valid());
    assertEquals(2023, badMonth.year());
    assertNull(badMonth.monthDay());
    assertNull(badMonth.ordinal());
    var zero =
        RoutineCli.call(new RoutineCli.Case("d", "INSDATE", null, 0, null, null, null, null));
    assertFalse(zero.valid());
    assertNull(zero.year());
  }

  @Test
  void insrateLeavesRateAndFeeUnwrittenBelowTheTable() {
    var below =
        RoutineCli.call(new RoutineCli.Case("r", "INSRATE", null, null, 18991231, 0, null, null));
    assertNull(below.rateBps());
    assertNull(below.feeBps());
    var first =
        RoutineCli.call(new RoutineCli.Case("r", "INSRATE", null, null, 20191231, 0, null, null));
    assertEquals(175, first.rateBps());
    assertEquals(600, first.feeBps());
    var waived =
        RoutineCli.call(new RoutineCli.Case("r", "INSRATE", null, null, 20191231, 70, null, null));
    assertEquals(175, waived.rateBps());
    assertEquals(0, waived.feeBps());
  }

  @Test
  void insvalAndInscalcReturnLogicalOutcomesWithoutScratch() {
    var val =
        RoutineCli.call(new RoutineCli.Case("v", "INSVAL", null, null, null, null, SREC, null));
    assertTrue(val.valid());
    var calc =
        RoutineCli.call(new RoutineCli.Case("c", "INSCALC", null, null, null, null, SREC, TREC));
    assertEquals("OKAY", calc.status());
    assertEquals(256, calc.srec().length());
    assertEquals(192, calc.orec().length());
    assertTrue(calc.srec().substring(96, 176).equals(TREC));
    assertNull(calc.fault());
    assertNull(calc.valid());
  }

  @Test
  void runReadsCasesAndWritesAReportWithIdentity(@TempDir Path dir) throws Exception {
    Path cases = dir.resolve("cases.json");
    Path out = dir.resolve("out.json");
    Files.write(
        cases,
        Json.bytes(
            new RoutineCli.Input(
                List.of(
                    new RoutineCli.Case(
                        "p1", "INSPACK", "0000000000000c", null, null, null, null, null),
                    new RoutineCli.Case(
                        "d1", "INSDATE", null, 20250101, null, null, null, null)))));
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    int rc =
        RoutineCli.run(
            new String[] {"--cases", cases.toString(), "--out", out.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr));
    assertEquals(0, rc, stderr.toString());
    RoutineCli.Report report = Json.read(out, RoutineCli.Report.class);
    assertEquals(RoutineCli.SCHEMA, report.schema());
    assertEquals(2, report.outputs().size());
    assertEquals("d1", report.outputs().get(1).id());
    assertEquals(2025, report.outputs().get(1).year());
    assertFalse(report.rateTableSha256().isEmpty());
    assertEquals(
        4, RoutineCli.run(new String[] {}, new PrintStream(stdout), new PrintStream(stderr)));
  }

  @Test
  void unknownRoutineIsRejected() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> RoutineCli.call(of("x", "INSNOPE")));
  }
}
