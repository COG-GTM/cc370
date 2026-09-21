package insurance.ledger;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * Independently pinned description of one batch stage: record counts and SHA-256 of the exact
 * POLIN/TXNIN bytes that are supposed to be processed. It is supplied separately from the delivered
 * input so that a genuinely empty dataset can be told apart from an unexpectedly empty or truncated
 * one, and so that publication is bound to what was expected rather than to what arrived.
 *
 * <p>{@code sourceSha256} is the SHA-256 of the manifest file bytes exactly as supplied (never
 * serialized); receipts bind it so a driver can check the receipt against the file it wrote.
 */
public record ExpectedManifest(
    @JsonProperty("schema") String schema,
    @JsonProperty("stage") String stage,
    @JsonProperty("policies_count") int policiesCount,
    @JsonProperty("transactions_count") int transactionsCount,
    @JsonProperty("polin_sha256") String polinSha256,
    @JsonProperty("txnin_sha256") String txninSha256,
    @JsonProperty("rates_sha256") String ratesSha256,
    @JsonIgnore String sourceSha256) {

  public static final String SCHEMA = "insurance-expected-manifest-v1";

  /** Parses manifest bytes and pins their hash as {@link #sourceSha256()}. */
  public static ExpectedManifest parse(byte[] raw) {
    ExpectedManifest m = Json.read(raw, ExpectedManifest.class);
    return new ExpectedManifest(
        m.schema,
        m.stage,
        m.policiesCount,
        m.transactionsCount,
        m.polinSha256,
        m.txninSha256,
        m.ratesSha256,
        Sha256.of(raw));
  }

  public static final class ManifestException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final List<String> problems;

    public ManifestException(List<String> problems) {
      super(String.join("; ", problems));
      this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
      return problems;
    }
  }

  /** Verifies delivered input bytes against this manifest; throws listing every problem. */
  public void verifyInputs(byte[] polin, byte[] txnin) {
    List<String> problems = new ArrayList<>();
    if (!SCHEMA.equals(schema)) {
      problems.add("manifest schema " + schema + " is not " + SCHEMA);
    }
    checkStream(problems, "POLIN", polin, PolicyRecord.LENGTH, policiesCount, polinSha256);
    checkStream(problems, "TXNIN", txnin, TransactionRecord.LENGTH, transactionsCount, txninSha256);
    if (!problems.isEmpty()) {
      throw new ManifestException(problems);
    }
  }

  /** Verifies produced outputs: one result per transaction, one master per policy. */
  public void verifyOutputs(byte[] polout, byte[] resout, int returnCode) {
    List<String> problems = new ArrayList<>();
    if (returnCode != 0) {
      problems.add("batch return code " + returnCode);
    }
    checkStream(problems, "POLOUT", polout, PolicyRecord.LENGTH, policiesCount, null);
    checkStream(problems, "RESOUT", resout, ResultRecord.LENGTH, transactionsCount, null);
    if (!problems.isEmpty()) {
      throw new ManifestException(problems);
    }
  }

  private static void checkStream(
      List<String> problems, String name, byte[] raw, int lrecl, int count, String sha) {
    int full = raw.length / lrecl;
    int partial = raw.length % lrecl;
    if (partial != 0) {
      problems.add(
          name + ": partial record (" + raw.length + " bytes is not a multiple of " + lrecl + ")");
    }
    if (full != count) {
      problems.add(
          name
              + ": "
              + full
              + " complete records, expected "
              + count
              + (full < count && partial == 0 ? " (aligned truncation or missing records)" : ""));
    }
    if (sha != null && !sha.equals(Sha256.of(raw))) {
      problems.add(name + ": sha256 " + Sha256.of(raw) + " does not match pinned " + sha);
    }
  }
}
