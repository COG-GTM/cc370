package insurance.app;

import insurance.ledger.ExpectedManifest;
import insurance.ledger.Sha256;
import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Fullword;
import insurance.legacy.codec.Layout.Policy;
import insurance.legacy.codec.Packed;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.TransactionRecord;
import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/** One PostgreSQL container per test JVM plus the record fixtures shared by the app tests. */
public final class PostgresSupport {
  private PostgresSupport() {}

  public static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:15-alpine")
          .withDatabaseName("ledger")
          .withUsername("ledger")
          .withPassword("ledger");

  static {
    PG.start();
  }

  public static void register(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", PG::getJdbcUrl);
    registry.add("spring.datasource.username", PG::getUsername);
    registry.add("spring.datasource.password", PG::getPassword);
  }

  /** Drops and re-creates the schema so a test starts from an empty ledger. */
  public static void resetSchema(DataSource ds) {
    Flyway flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
    flyway.clean();
    flyway.migrate();
  }

  // ---------------------------------------------------------------- record fixtures

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
        null,
        null);
  }
}
