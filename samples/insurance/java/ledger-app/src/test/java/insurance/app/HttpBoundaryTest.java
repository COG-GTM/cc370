package insurance.app;

import static insurance.app.PostgresSupport.VALUATION;
import static insurance.app.PostgresSupport.a001;
import static insurance.app.PostgresSupport.concat;
import static insurance.app.PostgresSupport.fresh;
import static insurance.app.PostgresSupport.manifest;
import static insurance.app.PostgresSupport.txn;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import insurance.app.http.Api.ApplyResponse;
import insurance.app.http.Api.BeginRequest;
import insurance.app.http.Api.ImportRequest;
import insurance.app.http.Api.LeaseResponse;
import insurance.app.http.Api.PublishRequest;
import insurance.app.http.Api.RawRequest;
import insurance.contract.v001.ContractV001;
import insurance.ledger.Json;
import insurance.ledger.Receipt;
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
import java.net.http.HttpTimeoutException;
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
 * Real HTTP client behaviour at both commit boundaries. The service runs in its own JVM ({@link
 * ChildService}); a real {@link HttpClient} with a request timeout talks to it through a TCP {@link
 * FaultProxy} that drops the request (nothing committed), drops the response (committed, response
 * lost) or answers a gateway {@code 503} before/after forwarding; a {@code fail:} fault point in
 * the service produces a genuine server {@code 500} inside (rolled back) or after (durable) the
 * commit transaction. The database is inspected directly after every fault, then the client retries
 * the same envelope and the contract decides: {@code OKAY} when nothing was committed, {@code DUPL}
 * when the lost commit is still the policy's latest, {@code ORDR} when another transaction
 * intervened. The published generation is compared with the INSBAT emulation of the physical
 * request order.
 *
 * <p>A timeout on the client is never taken as proof of rollback; the database is.
 */
class HttpBoundaryTest {
  private static final String NS = "/v1/namespaces/http";
  private static final Duration CLIENT_TIMEOUT = Duration.ofMillis(1500);
  private static final String P2 = "00000002";

  private final RuntimeEvidence evidence =
      new RuntimeEvidence("insurance-java-http-boundary-v1", "http-boundary");
  private ChildService child;
  private FaultProxy proxy;

  private static byte[] seed() {
    return concat(a001().bytes(), fresh(P2, 500_000, 20_000, 0).bytes());
  }

  @BeforeEach
  void reset() {
    PostgresSupport.resetSchema(
        new DriverManagerDataSource(
            PostgresSupport.PG.getJdbcUrl(),
            PostgresSupport.PG.getUsername(),
            PostgresSupport.PG.getPassword()));
  }

  /** One client (hence one fresh TCP connection) per call: no keep-alive across proxy modes. */
  private static HttpClient client() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  @AfterEach
  void stop(TestInfo info) throws IOException {
    if (proxy != null) {
      proxy.close();
    }
    if (child != null) {
      child.close();
    }
    evidence.write(info.getTestMethod().orElseThrow().getName());
  }

  // ---------------------------------------------------------------- proxy faults

  @Test
  void clientTimeoutBeforeAndAfterCommitAndGateway503AtBothBoundaries() throws Exception {
    child = ChildService.start(Duration.ofSeconds(60), null);
    proxy = new FaultProxy("127.0.0.1", child.port());
    // physical order the generation will end up with (manifest pinned before any request)
    List<TransactionRecord> expected =
        List.of(
            txn(P2, 1, VALUATION, 'P', 10_000), // timeout before commit -> retry OKAY (ord 1)
            txn(P2, 2, VALUATION, 'P', 10_000), // committed, response lost (ord 2)
            txn(P2, 2, VALUATION, 'P', 10_000), // retry -> DUPL (ord 3)
            txn(P2, 3, VALUATION, 'W', 5_000), // committed, response lost (ord 4)
            txn(P2, 4, VALUATION, 'L', 1_000), // intervening OKAY (ord 5)
            txn(P2, 3, VALUATION, 'W', 5_000), // retry -> ORDR (ord 6)
            txn(P2, 5, VALUATION, 'P', 2_000), // gateway 503 unsent -> retry OKAY (ord 7)
            txn(P2, 6, VALUATION, 'P', 2_000), // gateway 503 after commit (ord 8)
            txn(P2, 6, VALUATION, 'P', 2_000)); // retry -> DUPL (ord 9)
    LeaseResponse lease = importAndBegin("g1", expected.size(), Records.join(expected));

    // (a) request dropped before the service sees it: the client times out, nothing committed
    Map<String, Object> a = evidence.section("timeout_before_commit");
    proxy.mode(FaultProxy.Mode.DROP_REQUEST);
    Exception e1 = assertThrows(IOException.class, () -> viaProxy(lease, expected.get(0)));
    assertInstanceOf(HttpTimeoutException.class, e1);
    a.put("client_exception", e1.getClass().getName());
    a.put("db_after_fault", expectDb(0, null));
    proxy.mode(FaultProxy.Mode.PASS);
    ApplyResponse r1 = retry(lease, expected.get(0));
    assertEquals(1, r1.ordinal());
    assertEquals("OKAY", r1.status());
    a.put("retry", Map.of("ordinal", r1.ordinal(), "status", r1.status()));

    // (b) response dropped: committed, the client times out; retry is DUPL (still latest)
    Map<String, Object> b = evidence.section("timeout_after_commit");
    int answered = proxy.upstreamResponsesSeen();
    proxy.mode(FaultProxy.Mode.DROP_RESPONSE);
    Exception e2 = assertThrows(IOException.class, () -> viaProxy(lease, expected.get(1)));
    assertInstanceOf(HttpTimeoutException.class, e2);
    assertEquals(answered + 1, proxy.upstreamResponsesSeen(), "the service did answer");
    b.put("client_exception", e2.getClass().getName());
    b.put("db_after_fault", expectDb(2, "OKAY"));
    proxy.mode(FaultProxy.Mode.PASS);
    ApplyResponse r2 = retry(lease, expected.get(2));
    assertEquals(3, r2.ordinal());
    assertEquals("DUPL", r2.status());
    b.put("retry", Map.of("ordinal", r2.ordinal(), "status", r2.status()));

    // (c) response dropped, then an intervening transaction: the late retry is ORDR
    Map<String, Object> c = evidence.section("timeout_after_commit_intervening");
    proxy.mode(FaultProxy.Mode.DROP_RESPONSE);
    assertThrows(IOException.class, () -> viaProxy(lease, expected.get(3)));
    c.put("db_after_fault", expectDb(4, "OKAY"));
    proxy.mode(FaultProxy.Mode.PASS);
    ApplyResponse r4 = retry(lease, expected.get(4));
    assertEquals("OKAY", r4.status());
    ApplyResponse r3 = retry(lease, expected.get(5));
    assertEquals(6, r3.ordinal());
    assertEquals("ORDR", r3.status());
    c.put("intervening", Map.of("ordinal", r4.ordinal(), "status", r4.status()));
    c.put("retry", Map.of("ordinal", r3.ordinal(), "status", r3.status()));

    // (d) gateway 503 without forwarding: nothing committed, retry OKAY
    Map<String, Object> d = evidence.section("gateway_503_before_commit");
    proxy.mode(FaultProxy.Mode.REPLY_503_UNSENT);
    HttpResponse<String> g1 = viaProxy(lease, expected.get(6));
    assertEquals(503, g1.statusCode());
    d.put("client_status", g1.statusCode());
    d.put("db_after_fault", expectDb(6, "ORDR"));
    proxy.mode(FaultProxy.Mode.PASS);
    ApplyResponse r5 = retry(lease, expected.get(6));
    assertEquals(7, r5.ordinal());
    assertEquals("OKAY", r5.status());
    d.put("retry", Map.of("ordinal", r5.ordinal(), "status", r5.status()));

    // (e) gateway 503 after the service answered: committed, retry DUPL
    Map<String, Object> e = evidence.section("gateway_503_after_commit");
    proxy.mode(FaultProxy.Mode.REPLY_503_AFTER);
    HttpResponse<String> g2 = viaProxy(lease, expected.get(7));
    assertEquals(503, g2.statusCode());
    e.put("client_status", g2.statusCode());
    e.put("db_after_fault", expectDb(8, "OKAY"));
    proxy.mode(FaultProxy.Mode.PASS);
    ApplyResponse r6 = retry(lease, expected.get(8));
    assertEquals(9, r6.ordinal());
    assertEquals("DUPL", r6.status());
    e.put("retry", Map.of("ordinal", r6.ordinal(), "status", r6.status()));

    evidence.put("publication", publishAndCompare(lease, expected));
  }

  // ---------------------------------------------------------------- server 5xx

  @Test
  void server500InsideTheCommitTransactionRollsBackAndTheRetrySucceeds() throws Exception {
    child = ChildService.start(Duration.ofSeconds(60), "fail:before-commit:1");
    List<TransactionRecord> expected =
        List.of(txn(P2, 1, VALUATION, 'P', 10_000), txn(P2, 2, VALUATION, 'W', 1_000));
    LeaseResponse lease = importAndBegin("g1", expected.size(), Records.join(expected));
    Map<String, Object> ev = evidence.section("server_500_before_commit");

    HttpResponse<String> failed = direct(lease, expected.get(0));
    assertEquals(500, failed.statusCode(), failed.body());
    assertTrue(child.output().contains("fault BEFORE_COMMIT at ordinal 1"), child.output());
    ev.put("client_status", failed.statusCode());
    ev.put("db_after_fault", expectDb(0, null));

    ApplyResponse r = apply(direct(lease, expected.get(0)));
    assertEquals(1, r.ordinal());
    assertEquals("OKAY", r.status());
    ev.put("retry", Map.of("ordinal", r.ordinal(), "status", r.status()));
    apply(direct(lease, expected.get(1)));
    evidence.put("publication", publishAndCompare(lease, expected));
  }

  @Test
  void server500AfterTheCommitKeepsTheCommitAndTheRetryIsDupl() throws Exception {
    child = ChildService.start(Duration.ofSeconds(60), "fail:after-commit:1");
    List<TransactionRecord> expected =
        List.of(txn(P2, 1, VALUATION, 'P', 10_000), txn(P2, 1, VALUATION, 'P', 10_000));
    LeaseResponse lease = importAndBegin("g1", expected.size(), Records.join(expected));
    Map<String, Object> ev = evidence.section("server_500_after_commit");

    HttpResponse<String> failed = direct(lease, expected.get(0));
    assertEquals(500, failed.statusCode(), failed.body());
    assertTrue(child.output().contains("fault AFTER_COMMIT at ordinal 1"), child.output());
    ev.put("client_status", failed.statusCode());
    ev.put("db_after_fault", expectDb(1, "OKAY"));

    ApplyResponse r = apply(direct(lease, expected.get(1)));
    assertEquals(2, r.ordinal());
    assertEquals("DUPL", r.status());
    ev.put("retry", Map.of("ordinal", r.ordinal(), "status", r.status()));
    evidence.put("publication", publishAndCompare(lease, expected));
  }

  // ---------------------------------------------------------------- helpers

  private Map<String, Object> expectDb(long lastOrdinal, String lastStatus) throws Exception {
    Map<String, Object> db = dbState("g1");
    assertEquals(lastOrdinal, db.get("last_ordinal"));
    assertEquals(lastOrdinal, db.get("entries"));
    assertEquals(lastStatus, db.get("last_status"));
    return db;
  }

  private Map<String, Object> publishAndCompare(
      LeaseResponse lease, List<TransactionRecord> expected) throws Exception {
    BatchRunner.Output oracle =
        new BatchRunner(ContractV001.frozen()).run(seed(), Records.join(expected));
    HttpResponse<String> p =
        send(
            child.url(NS + "/generations/" + lease.generation() + "/publish"),
            new PublishRequest(lease.fence(), "http"),
            Duration.ofSeconds(30));
    assertEquals(200, p.statusCode(), p.body());
    Receipt receipt = Json.read(p.body().getBytes(StandardCharsets.UTF_8), Receipt.class);
    byte[] resout = bytes(NS + "/generations/" + lease.generation() + "/resout");
    byte[] requests = bytes(NS + "/generations/" + lease.generation() + "/requests");
    assertArrayEquals(Records.join(expected), requests, "physical order incl. retries");
    assertArrayEquals(oracle.resout(), resout, "retry outcomes equal the batch contract");
    assertArrayEquals(
        oracle.polout(), bytes(NS + "/generations/" + lease.generation() + "/polout"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("results_count", receipt.resultsCount());
    out.put("resout_sha256", receipt.resoutSha256());
    out.put("matches_oracle", true);
    return out;
  }

  private ApplyResponse retry(LeaseResponse lease, TransactionRecord t) throws Exception {
    return apply(viaProxy(lease, t));
  }

  private static ApplyResponse apply(HttpResponse<String> r) {
    assertEquals(200, r.statusCode(), r.body());
    return Json.read(r.body().getBytes(StandardCharsets.UTF_8), ApplyResponse.class);
  }

  private HttpResponse<String> viaProxy(LeaseResponse lease, TransactionRecord t) throws Exception {
    return send(
        "http://127.0.0.1:" + proxy.port() + rawPath(lease),
        new RawRequest(lease.fence(), Hex.of(t.bytes())),
        CLIENT_TIMEOUT);
  }

  private HttpResponse<String> direct(LeaseResponse lease, TransactionRecord t) throws Exception {
    return send(
        child.url(rawPath(lease)),
        new RawRequest(lease.fence(), Hex.of(t.bytes())),
        Duration.ofSeconds(30));
  }

  private static String rawPath(LeaseResponse lease) {
    return NS + "/generations/" + lease.generation() + "/requests:raw";
  }

  private HttpResponse<String> send(String url, Object body, Duration timeout) throws Exception {
    return client()
        .send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private byte[] bytes(String path) throws Exception {
    HttpResponse<byte[]> r =
        client()
            .send(
                HttpRequest.newBuilder(URI.create(child.url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, r.statusCode());
    return r.body();
  }

  private LeaseResponse importAndBegin(String gen, int txns, byte[] txnin) throws Exception {
    HttpResponse<String> imported =
        send(
            child.url(NS + "/import"),
            new ImportRequest(
                "root",
                b64(seed()),
                b64(Json.bytes(manifest("bootstrap", 2, 0, seed(), new byte[0])))),
            Duration.ofSeconds(30));
    assertEquals(201, imported.statusCode(), imported.body());
    HttpResponse<String> begun =
        send(
            child.url(NS + "/generations"),
            new BeginRequest(
                "root", gen, b64(seed()), b64(Json.bytes(manifest("a", 2, txns, seed(), txnin)))),
            Duration.ofSeconds(30));
    assertEquals(201, begun.statusCode(), begun.body());
    return Json.read(begun.body().getBytes(StandardCharsets.UTF_8), LeaseResponse.class);
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
              "SELECT g.status, g.last_ordinal,"
                  + " (SELECT count(*) FROM generation_entry e WHERE e.generation_id = g.id),"
                  + " (SELECT e.result FROM generation_entry e WHERE e.generation_id = g.id"
                  + "   ORDER BY e.ordinal DESC LIMIT 1)"
                  + " FROM generation g WHERE g.namespace = 'http' AND g.name = ?")) {
        ps.setString(1, gen);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next(), "generation row " + gen);
          m.put("status", rs.getString(1));
          m.put("last_ordinal", rs.getLong(2));
          m.put("entries", rs.getLong(3));
          byte[] last = rs.getBytes(4);
          m.put("last_status", last == null ? null : new ResultRecord(last).status());
        }
      }
    }
    return m;
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}
