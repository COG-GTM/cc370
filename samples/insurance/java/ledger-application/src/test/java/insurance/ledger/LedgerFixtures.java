package insurance.ledger;

import insurance.contract.v001.RateTable;
import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Fullword;
import insurance.legacy.codec.Layout.Policy;
import insurance.legacy.codec.Packed;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.TransactionRecord;
import java.util.Arrays;

public final class LedgerFixtures {
  private LedgerFixtures() {}

  /** Hash of the frozen V001 rate table; every test manifest binds it. */
  public static final String RATES_SHA256 = BuildIdentity.rateTableSha256(RateTable.FROZEN);

  public static final int ISSUE = 20240101;
  public static final int VALUATION = 20250101;

  public static PolicyRecord fresh(String id, long face, long cash, long loan) {
    byte[] raw = new byte[PolicyRecord.LENGTH];
    System.arraycopy(Cp037.encode(id), 0, raw, Policy.ID, 8);
    Fullword.put(raw, Policy.ISSUE, ISSUE);
    Fullword.put(raw, Policy.DATE, ISSUE);
    Fullword.put(raw, Policy.SEQ, 0);
    Packed.put(raw, Policy.FACE, 7, face);
    Packed.put(raw, Policy.CASH, 7, cash);
    Packed.put(raw, Policy.LOAN, 7, loan);
    return new PolicyRecord(raw);
  }

  /** The A001 anchor master. */
  public static PolicyRecord a001() {
    return fresh("00000001", 1_000_000, 100_000, 0);
  }

  public static TransactionRecord txn(String id, int seq, int date, char op, long amount) {
    return TransactionRecord.of(
        Cp037.encode(id), seq, date, Cp037.encode(String.valueOf(op))[0], amount);
  }

  public static byte[] concat(byte[]... parts) {
    int n = Arrays.stream(parts).mapToInt(p -> p.length).sum();
    byte[] out = new byte[n];
    int pos = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, pos, p.length);
      pos += p.length;
    }
    return out;
  }

  public static ExpectedManifest manifest(
      String stage, int policies, int txns, byte[] polin, byte[] txnin) {
    return new ExpectedManifest(
        ExpectedManifest.SCHEMA,
        stage,
        policies,
        txns,
        Sha256.of(polin),
        Sha256.of(txnin),
        RATES_SHA256,
        null);
  }
}
