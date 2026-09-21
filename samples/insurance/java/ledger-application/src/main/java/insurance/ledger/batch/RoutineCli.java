package insurance.ledger.batch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import insurance.contract.v001.ContractDomainException;
import insurance.contract.v001.ContractV001;
import insurance.contract.v001.Evaluation;
import insurance.contract.v001.LegacyCalendar;
import insurance.contract.v001.RateTable;
import insurance.contract.v001.StateValidator;
import insurance.ledger.BuildIdentity;
import insurance.ledger.Json;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.Packed;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.TransactionRecord;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Direct-call oracle for the five library routines, one logical call per case, for comparison
 * against guest observations of the same routines.
 *
 * <pre>
 * routines --cases F --out F
 * </pre>
 *
 * <p>Every case names a routine and carries only that routine's logical inputs: INSPACK the 7-byte
 * argument, INSDATE the WDATE fullword, INSRATE the TDATE fullword and WAGE, INSVAL the 128-byte
 * SREC, INSCALC SREC and the 40-byte TREC. The output carries only what the routine writes: a
 * validity verdict, the date parts INSDATE assigns, the rate/fee INSRATE writes, or the SREC/OREC
 * bytes INSCALC leaves behind. Nothing here models assembler linkage, registers, save areas or
 * scratch storage.
 */
public final class RoutineCli {
  private RoutineCli() {}

  /** One logical call; fields not used by the named routine are absent. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Case(
      @JsonProperty("id") String id,
      @JsonProperty("routine") String routine,
      @JsonProperty("arg") String arg,
      @JsonProperty("wdate") Integer wdate,
      @JsonProperty("tdate") Integer tdate,
      @JsonProperty("wage") Integer wage,
      @JsonProperty("srec") String srec,
      @JsonProperty("trec") String trec) {}

  /** What the routine wrote; absent fields were not written. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Output(
      @JsonProperty("id") String id,
      @JsonProperty("routine") String routine,
      @JsonProperty("valid") Boolean valid,
      @JsonProperty("year") Integer year,
      @JsonProperty("monthDay") Integer monthDay,
      @JsonProperty("ordinal") Integer ordinal,
      @JsonProperty("rateBps") Integer rateBps,
      @JsonProperty("feeBps") Integer feeBps,
      @JsonProperty("srec") String srec,
      @JsonProperty("orec") String orec,
      @JsonProperty("status") String status,
      @JsonProperty("fault") String fault) {}

  public record Input(@JsonProperty("cases") List<Case> cases) {}

  public record Report(
      @JsonProperty("schema") String schema,
      @JsonProperty("build_identity") String buildIdentity,
      @JsonProperty("runtime_identity") String runtimeIdentity,
      @JsonProperty("rate_table_sha256") String rateTableSha256,
      @JsonProperty("outputs") List<Output> outputs) {}

  public static final String SCHEMA = "insurance-java-routines-v1";

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  public static int run(String[] args, PrintStream out, PrintStream err) {
    Map<String, String> opts = BatchCli.parse(args, 0);
    String cases = opts.get("cases");
    String outPath = opts.get("out");
    if (cases == null || outPath == null) {
      err.println("usage: routines --cases F --out F");
      return 4;
    }
    try {
      Input input = Json.read(Path.of(cases), Input.class);
      List<Output> outputs = new ArrayList<>();
      for (Case c : input.cases()) {
        outputs.add(call(c));
      }
      Report report =
          new Report(
              SCHEMA,
              BuildIdentity.ofClass(RoutineCli.class),
              BuildIdentity.runtime(),
              BuildIdentity.rateTableSha256(RateTable.FROZEN),
              outputs);
      Files.write(Path.of(outPath), Json.bytes(report));
      out.println(outputs.size() + " routine calls");
      return 0;
    } catch (IOException e) {
      err.println(e.getMessage());
      return 3;
    }
  }

  static Output call(Case c) {
    return switch (c.routine()) {
      case "INSPACK" -> inspack(c);
      case "INSDATE" -> insdate(c);
      case "INSRATE" -> insrate(c);
      case "INSVAL" -> insval(c);
      case "INSCALC" -> inscalc(c);
      default -> throw new IllegalArgumentException("unknown routine " + c.routine());
    };
  }

  private static Output inspack(Case c) {
    byte[] arg = Hex.parse(c.arg());
    boolean valid = Packed.isValid(arg, 0, arg.length);
    return new Output(
        c.id(), c.routine(), valid, null, null, null, null, null, null, null, null, null);
  }

  private static Output insdate(Case c) {
    int d = c.wdate();
    OptionalInt year = LegacyCalendar.yearPart(d);
    var parts = LegacyCalendar.parts(d);
    OptionalInt ordinal = LegacyCalendar.ordinal(d);
    return new Output(
        c.id(),
        c.routine(),
        parts.isPresent(),
        boxed(year),
        parts.map(LegacyCalendar.Parts::monthDay).orElse(null),
        boxed(ordinal),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static Output insrate(Case c) {
    RateTable.Direct d = RateTable.FROZEN.direct(c.tdate(), c.wage());
    return new Output(
        c.id(),
        c.routine(),
        null,
        null,
        null,
        null,
        boxed(d.rateBps()),
        boxed(d.feeBps()),
        null,
        null,
        null,
        null);
  }

  private static Output insval(Case c) {
    boolean valid = StateValidator.isValid(new PolicyRecord(Hex.parse(c.srec())));
    return new Output(
        c.id(), c.routine(), valid, null, null, null, null, null, null, null, null, null);
  }

  private static Output inscalc(Case c) {
    PolicyRecord master = new PolicyRecord(Hex.parse(c.srec()));
    TransactionRecord txn = new TransactionRecord(Hex.parse(c.trec()));
    try {
      Evaluation e = ContractV001.frozen().evaluate(master, txn);
      return new Output(
          c.id(),
          c.routine(),
          null,
          null,
          null,
          null,
          null,
          null,
          Hex.of(e.master().bytes()),
          Hex.of(e.result().bytes()),
          e.status().name(),
          null);
    } catch (ContractDomainException fault) {
      return new Output(
          c.id(),
          c.routine(),
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          fault.getMessage());
    }
  }

  private static Integer boxed(OptionalInt v) {
    return v.isPresent() ? v.getAsInt() : null;
  }
}
