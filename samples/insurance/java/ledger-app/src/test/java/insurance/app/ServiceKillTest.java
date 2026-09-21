package insurance.app;

import static insurance.app.PostgresSupport.VALUATION;
import static insurance.app.PostgresSupport.a001;
import static insurance.app.PostgresSupport.concat;
import static insurance.app.PostgresSupport.fresh;
import static insurance.app.PostgresSupport.manifest;
import static insurance.app.PostgresSupport.txn;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import insurance.app.http.Api.ApplyResponse;
import insurance.app.http.Api.BeginRequest;
import insurance.app.http.Api.ImportRequest;
import insurance.app.http.Api.LeaseResponse;
import insurance.app.http.Api.PublishRequest;
import insurance.app.http.Api.RawRequest;
import insurance.contract.v001.ContractV001;
import insurance.ledger.Json;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.ledger.batch.BatchRunner;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Real termination of the PostgreSQL-backed service: the HTTP service runs in a child JVM ({@link
 * ChildService}) whose {@code ledger.kill-switch} halts it ({@code Runtime.halt(137)}: no shutdown
 * hook, no transaction commit, no HTTP response) at a defined boundary while a real HTTP client is
 * waiting. The database is then inspected directly, a second service process is started on the same
 * database, and the generation is re-driven from the pinned published parent and compared with the
 * pure INSBAT emulation.
 *
 * <p>Bounded claims: the pending generation of the dead writer is discarded once its lease has
 * expired (fail closed, deviation D2: no verified-prefix resume); before its lease expires a new
 * instance leaves it alone and cannot write to it. Durability is PostgreSQL's; no power-loss test.
 */
class ServiceKillTest {
  private static final String NS = "/v1/namespaces/svc";
  private static final Duration LEASE = Duration.ofSeconds(2);

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final RuntimeEvidence evidence =
      new RuntimeEvidence("insurance-java-service-kill-v1", "service-kill");
  private ChildService child;

  private static byte[] seed() {
    return concat(a001().bytes(), fresh("00000002", 500_000, 20_000, 0).bytes());
  }

  private static List<TransactionRecord> requests() {
    return List.of(
        txn("00000001", 1, VALUATION, 'P', 10_000),
        txn("00000001", 1, VALUATION, 'P', 10_000),
        txn("00000002", 1, VALUATION, 'W', 5_000),
        txn("00000002", 2, VALUATION, 'L', 1_000));
  }

  private static byte[] txnin() {
    return Records.join(requests());
  }

  private static BatchRunner.Output oracle() {
    return new BatchRunner(ContractV001.frozen()).run(seed(), txnin());
  }

  @BeforeEach
  void reset() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            PostgresSupport.PG.getJdbcUrl(),
            PostgresSupport.PG.getUsername(),
            PostgresSupport.PG.getPassword());
    PostgresSupport.resetSchema(ds);
  }

  @AfterEach
  void stop(TestInfo info) {
    if (child != null) {
      child.close();
    }
    evidence.write(info.getTestMethod().orElseThrow().getName());
  }

  // ---------------------------------------------------------------- scenarios

  @Test
  void killBeforeCommitLeavesNoOrdinalAndTheRerunFromTheParentMatchesTheOracle() throws Exception {
    Map<String, Object> ev = evidence.section("before_commit_3");
    child = ChildService.start(LEASE, "before-commit:3");
    LeaseResponse lease = importAndBegin("g1");
    assertEquals("OKAY", apply(lease, requests().get(0)).status());
    assertEquals("DUPL", apply(lease, requests().get(1)).status());
    assertThrows(IOException.class, () -> raw(lease, requests().get(2)), "connection died");
    int exit = child.waitFor();
    assertEquals(ChildService.HALTED, exit, child.output());
    assertTrue(child.output().contains("kill switch BEFORE_COMMIT at ordinal 3"), child.output());
    ev.put("child_exit", exit);
    ev.put("kill_line", killLine());

    Map<String, Object> db = dbState("g1");
    assertEquals("PENDING", db.get("status"));
    assertEquals(2L, db.get("last_ordinal"), "the third commit was rolled back");
    assertEquals(2L, db.get("entries"));
    ev.put("db_after_kill", db);

    restartAfterLeaseExpiry();
    Map<String, Object> after = dbState("g1");
    assertEquals("DISCARDED", after.get("status"));
    assertEquals("orphaned: writer lease expired at open", after.get("discard_reason"));
    assertEquals(409, raw(lease, requests().get(2)).statusCode(), "dead writer's fence");
    ev.put("db_after_restart", after);
    ev.put("rerun", rerunAndPublish("g2"));
  }

  @Test
  void killAfterCommitLosesTheResponseButNotTheCommit() throws Exception {
    Map<String, Object> ev = evidence.section("after_commit_3");
    child = ChildService.start(LEASE, "after-commit:3");
    LeaseResponse lease = importAndBegin("g1");
    apply(lease, requests().get(0));
    apply(lease, requests().get(1));
    assertThrows(IOException.class, () -> raw(lease, requests().get(2)), "response lost");
    int exit = child.waitFor();
    assertEquals(ChildService.HALTED, exit, child.output());
    assertTrue(child.output().contains("kill switch AFTER_COMMIT at ordinal 3"), child.output());
    ev.put("child_exit", exit);
    ev.put("kill_line", killLine());

    Map<String, Object> db = dbState("g1");
    assertEquals("PENDING", db.get("status"));
    assertEquals(3L, db.get("last_ordinal"), "committed although the client never saw it");
    assertEquals(3L, db.get("entries"));
    assertEquals(
        oracle().evaluations().get(2).result().status(),
        db.get("last_status"),
        "the unacknowledged commit holds the oracle result");
    ev.put("db_after_kill", db);

    restartAfterLeaseExpiry();
    Map<String, Object> after = dbState("g1");
    assertEquals("DISCARDED", after.get("status"));
    assertEquals(3L, after.get("entries"), "discard keeps the evidence, publishes nothing");
    ev.put("db_after_restart", after);
    ev.put("rerun", rerunAndPublish("g2"));
  }

  @Test
  void killInsidePublicationRollsBackTheOutputAndKeepsTheParentCurrent() throws Exception {
    Map<String, Object> ev = evidence.section("before_publish_flip");
    child = ChildService.start(LEASE, "before-publish-flip");
    LeaseResponse lease = importAndBegin("g1");
    for (TransactionRecord t : requests()) {
      apply(lease, t);
    }
    assertThrows(IOException.class, () -> publish(lease), "connection died mid-publication");
    int exit = child.waitFor();
    assertEquals(ChildService.HALTED, exit, child.output());
    assertTrue(child.output().contains("kill switch BEFORE_PUBLISH_FLIP"), child.output());
    ev.put("child_exit", exit);
    ev.put("kill_line", killLine());

    Map<String, Object> db = dbState("g1");
    assertEquals("PENDING", db.get("status"));
    assertEquals(4L, db.get("last_ordinal"));
    assertFalse((Boolean) db.get("has_output"), "output row rolled back with the transaction");
    assertEquals("root", db.get("current"), "namespace pointer never moved");
    ev.put("db_after_kill", db);

    restartAfterLeaseExpiry();
    Map<String, Object> after = dbState("g1");
    assertEquals("DISCARDED", after.get("status"));
    assertEquals("root", after.get("current"));
    assertEquals(404, get(NS + "/generations/g1/receipt").statusCode());
    ev.put("db_after_restart", after);
    ev.put("rerun", rerunAndPublish("g2"));
  }

  @Test
  void killAfterPublicationLosesOnlyTheReceiptResponse() throws Exception {
    Map<String, Object> ev = evidence.section("after_publish");
    child = ChildService.start(LEASE, "after-publish");
    LeaseResponse lease = importAndBegin("g1");
    for (TransactionRecord t : requests()) {
      apply(lease, t);
    }
    assertThrows(IOException.class, () -> publish(lease), "receipt response lost");
    int exit = child.waitFor();
    assertEquals(ChildService.HALTED, exit, child.output());
    assertTrue(child.output().contains("kill switch AFTER_PUBLISH at ordinal 4"), child.output());
    ev.put("child_exit", exit);
    ev.put("kill_line", killLine());

    Map<String, Object> db = dbState("g1");
    assertEquals("PUBLISHED", db.get("status"));
    assertTrue((Boolean) db.get("has_output"));
    assertEquals("g1", db.get("current"));
    ev.put("db_after_kill", db);

    child = ChildService.start(LEASE, null);
    assertEquals("g1", node(get(NS + "/current").body()).get("current").asText());
    Receipt receipt =
        Json.read(
            get(NS + "/generations/g1/receipt").body().getBytes(StandardCharsets.UTF_8),
            Receipt.class);
    assertEquals("PUBLISHED", receipt.publicationStatus());
    assertEquals(4, receipt.transactionsCount());
    BatchRunner.Output oracle = oracle();
    byte[] resout = bytes(NS + "/generations/g1/resout");
    byte[] polout = bytes(NS + "/generations/g1/polout");
    assertArrayEquals(oracle.resout(), resout);
    assertArrayEquals(oracle.polout(), polout);
    assertEquals(Sha256.of(resout), receipt.resoutSha256());
    assertEquals(409, raw(lease, requests().get(0)).statusCode(), "published: no more writes");
    ev.put("receipt_after_restart", receipt);
    ev.put("resout_matches_oracle", true);
  }

  // ---------------------------------------------------------------- helpers

  private void restartAfterLeaseExpiry() throws Exception {
    Thread.sleep(LEASE.toMillis() + 500);
    child = ChildService.start(LEASE, null);
  }

  /** Reruns every request from the published root into {@code gen}, publishes and checks. */
  private Map<String, Object> rerunAndPublish(String gen) throws Exception {
    LeaseResponse lease = begin(gen);
    BatchRunner.Output oracle = oracle();
    for (int i = 0; i < requests().size(); i++) {
      ApplyResponse a = apply(lease, requests().get(i));
      assertEquals(i + 1, a.ordinal());
      assertEquals(oracle.evaluations().get(i).result().status(), a.status());
    }
    HttpResponse<String> p = publish(lease);
    assertEquals(200, p.statusCode(), p.body());
    Receipt receipt = Json.read(p.body().getBytes(StandardCharsets.UTF_8), Receipt.class);
    assertArrayEquals(oracle.resout(), bytes(NS + "/generations/" + gen + "/resout"));
    assertArrayEquals(oracle.polout(), bytes(NS + "/generations/" + gen + "/polout"));
    assertEquals(gen, dbState(gen).get("current"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("generation", gen);
    out.put("resout_sha256", receipt.resoutSha256());
    out.put("polout_sha256", receipt.poloutSha256());
    out.put("matches_oracle", true);
    return out;
  }

  private String killLine() {
    return child.output().lines().filter(l -> l.startsWith("kill switch")).findFirst().orElse("");
  }

  private Map<String, Object> dbState(String gen) throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    try (Connection c =
        DriverManager.getConnection(
            PostgresSupport.PG.getJdbcUrl(),
            PostgresSupport.PG.getUsername(),
            PostgresSupport.PG.getPassword())) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT g.status, g.last_ordinal, g.discard_reason,"
                  + " (SELECT count(*) FROM generation_entry e WHERE e.generation_id = g.id),"
                  + " (SELECT e.result FROM generation_entry e WHERE e.generation_id = g.id"
                  + "   ORDER BY e.ordinal DESC LIMIT 1),"
                  + " EXISTS (SELECT 1 FROM generation_output o WHERE o.generation_id = g.id),"
                  + " (SELECT c.name FROM generation c JOIN namespace n"
                  + "   ON n.current_generation_id = c.id WHERE n.name = g.namespace)"
                  + " FROM generation g WHERE g.namespace = 'svc' AND g.name = ?")) {
        ps.setString(1, gen);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next(), "generation row " + gen);
          m.put("status", rs.getString(1));
          m.put("last_ordinal", rs.getLong(2));
          m.put("discard_reason", rs.getString(3));
          m.put("entries", rs.getLong(4));
          byte[] last = rs.getBytes(5);
          m.put("last_status", last == null ? null : new ResultRecord(last).status());
          m.put("has_output", rs.getBoolean(6));
          m.put("current", rs.getString(7));
        }
      }
    }
    return m;
  }

  private LeaseResponse importAndBegin(String gen) throws Exception {
    HttpResponse<String> imported =
        post(
            NS + "/import",
            new ImportRequest(
                "root",
                b64(seed()),
                b64(Json.bytes(manifest("bootstrap", 2, 0, seed(), new byte[0])))));
    assertEquals(201, imported.statusCode(), imported.body());
    return begin(gen);
  }

  private LeaseResponse begin(String gen) throws Exception {
    HttpResponse<String> begun =
        post(
            NS + "/generations",
            new BeginRequest(
                "root", gen, b64(seed()), b64(Json.bytes(manifest("a", 2, 4, seed(), txnin())))));
    assertEquals(201, begun.statusCode(), begun.body());
    return Json.read(begun.body().getBytes(StandardCharsets.UTF_8), LeaseResponse.class);
  }

  private ApplyResponse apply(LeaseResponse lease, TransactionRecord t) throws Exception {
    HttpResponse<String> r = raw(lease, t);
    assertEquals(200, r.statusCode(), r.body());
    return Json.read(r.body().getBytes(StandardCharsets.UTF_8), ApplyResponse.class);
  }

  private HttpResponse<String> raw(LeaseResponse lease, TransactionRecord t) throws Exception {
    return post(
        NS + "/generations/" + lease.generation() + "/requests:raw",
        new RawRequest(lease.fence(), Hex.of(t.bytes())));
  }

  private HttpResponse<String> publish(LeaseResponse lease) throws Exception {
    return post(
        NS + "/generations/" + lease.generation() + "/publish",
        new PublishRequest(lease.fence(), "http"));
  }

  private HttpResponse<String> post(String path, Object body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(child.url(path)))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(child.url(path))).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private byte[] bytes(String path) throws Exception {
    HttpResponse<byte[]> r =
        client.send(
            HttpRequest.newBuilder(URI.create(child.url(path))).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, r.statusCode());
    return r.body();
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static JsonNode node(String json) {
    return Json.read(json.getBytes(StandardCharsets.UTF_8), JsonNode.class);
  }
}
