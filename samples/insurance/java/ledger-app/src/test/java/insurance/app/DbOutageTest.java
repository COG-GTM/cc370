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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import insurance.app.http.Api.ApplyResponse;
import insurance.app.http.Api.BeginRequest;
import insurance.app.http.Api.ClaimRequest;
import insurance.app.http.Api.ClaimResponse;
import insurance.app.http.Api.ImportRequest;
import insurance.app.http.Api.LeaseResponse;
import insurance.app.http.Api.PublishRequest;
import insurance.app.http.Api.RawRequest;
import insurance.contract.v001.ContractV001;
import insurance.ledger.Json;
import insurance.ledger.PolicyLedgerService;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.ledger.batch.BatchRunner;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * An actual PostgreSQL outage in the middle of a generation. The service is the real {@code
 * LedgerApplication} in a child JVM ({@link ChildService}); the database is a container owned by
 * this class alone (fixed host port so it comes back at the same address) that is stopped ({@code
 * docker stop}: fast shutdown, connections refused) or frozen ({@code docker pause}: SIGSTOP, the
 * server never answers, the JDBC side times out) while the service is live and a real HTTP client
 * is calling. Nothing is injected into the service: no kill switch, no fault point, no thrown
 * exception.
 *
 * <p>Verified: the committed prefix before the outage; that the database is unreachable (a direct
 * JDBC connect from the test fails, the service answers 5xx and writes no ordinal / receipt /
 * publication); that after {@code docker start} / {@code unpause} the generation holds exactly the
 * pre-outage prefix, the same writer's retry and the remaining requests commit in order, the lease
 * heartbeat resumes and the published bytes equal the pure INSBAT emulation; and that a service
 * restart during the outage fails closed (the child exits, serves nothing), and that once the
 * database is back the restarted service finds the abandoned generation still pending, claims it
 * after the dead writer's lease has expired and resumes it at the next ordinal (writer takeover
 * proper is exercised in {@link ServiceKillTest}).
 *
 * <p>Bounded: one service process, one database container on one host; durability across the stop
 * is PostgreSQL's fast shutdown, not a power cut, partition or failover.
 */
class DbOutageTest {
  private static final String NS = "/v1/namespaces/outage";
  private static final Duration LEASE = Duration.ofSeconds(15);
  private static final String[] FAST_FAIL = {
    "--spring.datasource.hikari.connection-timeout=3000",
    "--spring.datasource.hikari.validation-timeout=1000",
    "--spring.datasource.hikari.data-source-properties.connectTimeout=3",
    "--spring.datasource.hikari.data-source-properties.socketTimeout=4"
  };

  private static PostgreSQLContainer<?> db;
  private static int hostPort;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final RuntimeEvidence evidence =
      new RuntimeEvidence("insurance-java-db-outage-v1", "db-outage");
  private ChildService child;

  @BeforeAll
  static void startDatabase() throws Exception {
    hostPort = ChildService.freePort();
    db =
        new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("ledger")
            .withUsername("ledger")
            .withPassword("ledger")
            .withCreateContainerCmdModifier(
                cmd ->
                    cmd.getHostConfig()
                        .withPortBindings(
                            new PortBinding(
                                Ports.Binding.bindPort(hostPort),
                                new ExposedPort(PostgreSQLContainer.POSTGRESQL_PORT))));
    db.start();
    assertEquals(hostPort, db.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
  }

  @AfterAll
  static void stopDatabase() {
    if (db != null) {
      db.stop();
    }
  }

  @BeforeEach
  void reset() {
    PostgresSupport.resetSchema(
        new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
    evidence.put("postgres_image", db.getDockerImageName());
    evidence.put("container_owned_by_test", true);
  }

  @AfterEach
  void stop(TestInfo info) {
    if (child != null) {
      child.close();
    }
    evidence.write(info.getTestMethod().orElseThrow().getName());
  }

  // ---------------------------------------------------------------- fixtures

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

  // ---------------------------------------------------------------- scenarios

  @Test
  void stopAfterCommittedPrefixFailsTheRequestAndTheSameWriterResumesAfterRestart()
      throws Exception {
    Map<String, Object> ev = evidence.section("stop_between_requests");
    child =
        ChildService.start(
            db.getJdbcUrl(), db.getUsername(), db.getPassword(), LEASE, null, FAST_FAIL);
    LeaseResponse lease = importAndBegin("g1");
    assertEquals("OKAY", apply(lease, requests().get(0)).status());
    assertEquals("DUPL", apply(lease, requests().get(1)).status());
    Map<String, Object> before = dbState("g1");
    assertEquals(2L, before.get("last_ordinal"));
    assertEquals(2L, before.get("entries"));
    ev.put("db_before_outage", before);

    Map<String, Object> outage = stopDatabase(ev);
    HttpResponse<String> failed = raw(lease, requests().get(2));
    assertEquals(500, failed.statusCode(), failed.body());
    outage.put("request_3_status", failed.statusCode());
    outage.put("request_3_body", failed.body());
    HttpResponse<String> read = get(NS + "/current");
    assertEquals(500, read.statusCode(), "reads fail closed too, no cached answer");
    outage.put("current_read_status", read.statusCode());
    assertTrue(child.isAlive(), "the service itself survives the outage");
    awaitHeartbeatFailure();
    outage.put("heartbeat_failure_logged", true);

    restartDatabase(ev);
    Map<String, Object> after = dbState("g1");
    assertEquals("PENDING", after.get("status"));
    assertEquals(2L, after.get("last_ordinal"), "the failed request left no ordinal");
    assertEquals(2L, after.get("entries"));
    assertEquals(before.get("entry_sha256"), after.get("entry_sha256"), "prefix bytes unchanged");
    assertFalse((Boolean) after.get("has_output"));
    assertEquals("root", after.get("current"));
    ev.put("db_after_restore", after);

    ApplyResponse retry = apply(lease, requests().get(2));
    assertEquals(3, retry.ordinal(), "retry takes the next ordinal, nothing was skipped");
    assertEquals(oracle().evaluations().get(2).result().status(), retry.status());
    ApplyResponse last = apply(lease, requests().get(3));
    assertEquals(4, last.ordinal());
    ev.put("retry", Map.of("ordinal", retry.ordinal(), "status", retry.status()));

    Timestamp renewed = leaseExpiry("g1");
    Thread.sleep(LEASE.toMillis() / 3 + 1_000);
    Timestamp renewedAgain = leaseExpiry("g1");
    assertTrue(renewedAgain.after(renewed), "the heartbeat renews again after the outage");
    ev.put(
        "heartbeat_after_restore",
        Map.of("lease_expires_at", renewed.toString(), "next", renewedAgain.toString()));
    ev.put("publication", publishAndCompare(lease, "g1"));
  }

  @Test
  void pauseFreezesTheDatabaseTheRequestTimesOutAndNothingCommitsOnThaw() throws Exception {
    Map<String, Object> ev = evidence.section("pause_in_flight");
    child =
        ChildService.start(
            db.getJdbcUrl(), db.getUsername(), db.getPassword(), LEASE, null, FAST_FAIL);
    LeaseResponse lease = importAndBegin("g1");
    apply(lease, requests().get(0));
    apply(lease, requests().get(1));
    Map<String, Object> before = dbState("g1");
    ev.put("db_before_outage", before);

    DockerClient docker = db.getDockerClient();
    docker.pauseContainerCmd(db.getContainerId()).exec();
    ev.put("paused", true);
    long t0 = System.nanoTime();
    HttpResponse<String> failed = raw(lease, requests().get(2));
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
    assertEquals(500, failed.statusCode(), failed.body());
    assertTrue(elapsedMs >= 1_000, "failed by a JDBC timeout, not refused at once: " + elapsedMs);
    ev.put("request_3", Map.of("status", failed.statusCode(), "elapsed_ms", elapsedMs));
    docker.unpauseContainerCmd(db.getContainerId()).exec();
    ev.put("unpaused", true);
    awaitDatabase();

    Map<String, Object> after = dbState("g1");
    assertEquals(2L, after.get("last_ordinal"), "the frozen statement did not commit later");
    assertEquals(2L, after.get("entries"));
    assertEquals(before.get("entry_sha256"), after.get("entry_sha256"));
    ev.put("db_after_restore", after);

    ApplyResponse retry = apply(lease, requests().get(2));
    assertEquals(3, retry.ordinal());
    assertEquals(oracle().evaluations().get(2).result().status(), retry.status());
    apply(lease, requests().get(3));
    ev.put("retry", Map.of("ordinal", retry.ordinal(), "status", retry.status()));
    ev.put("publication", publishAndCompare(lease, "g1"));
  }

  @Test
  void stopDuringPublicationLeavesNoReceiptAndThePublishRetrySucceedsAfterRestart()
      throws Exception {
    Map<String, Object> ev = evidence.section("stop_at_publication");
    child =
        ChildService.start(
            db.getJdbcUrl(), db.getUsername(), db.getPassword(), LEASE, null, FAST_FAIL);
    LeaseResponse lease = importAndBegin("g1");
    for (TransactionRecord t : requests()) {
      apply(lease, t);
    }
    Map<String, Object> before = dbState("g1");
    assertEquals(4L, before.get("last_ordinal"));
    ev.put("db_before_outage", before);

    Map<String, Object> outage = stopDatabase(ev);
    HttpResponse<String> failed = publish(lease);
    assertEquals(500, failed.statusCode(), failed.body());
    assertFalse(failed.body().contains("publication_status"), "no receipt in a failed publish");
    outage.put("publish_status", failed.statusCode());
    outage.put("publish_body", failed.body());

    restartDatabase(ev);
    Map<String, Object> after = dbState("g1");
    assertEquals("PENDING", after.get("status"), "neither published nor discarded");
    assertFalse((Boolean) after.get("has_output"));
    assertEquals("root", after.get("current"));
    assertEquals(4L, after.get("entries"));
    assertEquals(404, get(NS + "/generations/g1/receipt").statusCode(), "no receipt exists");
    ev.put("db_after_restore", after);
    ev.put("publication", publishAndCompare(lease, "g1"));
  }

  @Test
  void restartDuringTheOutageFailsClosedAndStartsAgainAfterRestore() throws Exception {
    Map<String, Object> ev = evidence.section("restart_during_outage");
    Duration shortLease = Duration.ofSeconds(2);
    child =
        ChildService.start(
            db.getJdbcUrl(), db.getUsername(), db.getPassword(), shortLease, null, FAST_FAIL);
    LeaseResponse lease = importAndBegin("g1");
    apply(lease, requests().get(0));
    apply(lease, requests().get(1));
    ev.put("db_before_outage", dbState("g1"));
    child.close();
    child = null;

    Map<String, Object> outage = stopDatabase(ev);
    ChildService.ExitedException exited =
        assertThrows(
            ChildService.ExitedException.class,
            () ->
                ChildService.start(
                    db.getJdbcUrl(),
                    db.getUsername(),
                    db.getPassword(),
                    shortLease,
                    null,
                    FAST_FAIL),
            "a service cannot start without its database");
    assertNotEquals(0, exited.exitValue());
    Matcher cause =
        Pattern.compile("PSQLException: Connection to \\S+ refused").matcher(exited.output());
    assertTrue(cause.find(), "startup failed on the refused connection:\n" + exited.output());
    outage.put("restart_exit", exited.exitValue());
    outage.put("restart_root_cause", cause.group());

    restartDatabase(ev);
    Thread.sleep(shortLease.toMillis() + 500);
    child =
        ChildService.start(
            db.getJdbcUrl(), db.getUsername(), db.getPassword(), shortLease, null, FAST_FAIL);
    Map<String, Object> after = dbState("g1");
    assertEquals("PENDING", after.get("status"), "startup leaves the abandoned generation alone");
    assertEquals(2L, after.get("entries"), "the committed prefix is intact");
    assertEquals("root", after.get("current"));
    assertEquals(409, raw(lease, requests().get(2)).statusCode(), "old fence rejected");
    ev.put("db_after_restart", after);

    HttpResponse<String> claimed =
        post(
            NS + "/generations/g1/claim",
            new ClaimRequest(b64(Json.bytes(manifest("a", 2, 4, seed(), txnin())))));
    assertEquals(200, claimed.statusCode(), claimed.body());
    ClaimResponse c =
        Json.read(claimed.body().getBytes(StandardCharsets.UTF_8), ClaimResponse.class);
    assertEquals(2, c.lastOrdinal());
    assertEquals(lease.fence() + 1, c.fence());
    LeaseResponse mine = new LeaseResponse(c.namespace(), c.generation(), c.parent(), c.fence());
    byte[] committed = bytes(NS + "/generations/g1/peek/requests");
    int next = PolicyLedgerService.resumeIndex(committed, requests());
    assertEquals(2, next);
    BatchRunner.Output oracle = oracle();
    for (int i = next; i < requests().size(); i++) {
      ApplyResponse a = apply(mine, requests().get(i));
      assertEquals(i + 1, a.ordinal());
      assertEquals(oracle.evaluations().get(i).result().status(), a.status());
    }
    assertEquals(409, raw(lease, requests().get(3)).statusCode(), "old fence still rejected");
    ev.put("claim", node(claimed.body()));
    ev.put("resumed_from_ordinal", next + 1);
    ev.put("resume", publishAndCompare(mine, "g1"));
  }

  // ---------------------------------------------------------------- outage control

  /** {@code docker stop}: PostgreSQL fast shutdown, then the port refuses connections. */
  private Map<String, Object> stopDatabase(Map<String, Object> ev) {
    Map<String, Object> outage = new LinkedHashMap<>();
    ev.put("outage", outage);
    db.getDockerClient().stopContainerCmd(db.getContainerId()).withTimeout(10).exec();
    outage.put("container_state", containerState());
    assertEquals("exited", outage.get("container_state"));
    SQLException direct =
        assertThrows(SQLException.class, this::directConnection, "database must be unreachable");
    outage.put(
        "direct_jdbc_connect", direct.getClass().getSimpleName() + ": " + direct.getMessage());
    return outage;
  }

  /** {@code docker start} on the same container (same data directory, same host port). */
  private void restartDatabase(Map<String, Object> ev) throws Exception {
    db.getDockerClient().startContainerCmd(db.getContainerId()).exec();
    awaitDatabase();
    Map<String, Object> restore = new LinkedHashMap<>();
    restore.put("container_state", containerState());
    restore.put("direct_jdbc_connect", "ok");
    ev.put("restore", restore);
  }

  private String containerState() {
    return db.getDockerClient()
        .inspectContainerCmd(db.getContainerId())
        .exec()
        .getState()
        .getStatus();
  }

  private void awaitDatabase() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    SQLException last = null;
    while (System.nanoTime() < deadline) {
      try (Connection c = directConnection()) {
        c.createStatement().execute("SELECT 1");
        return;
      } catch (SQLException e) {
        last = e;
        Thread.sleep(250);
      }
    }
    throw new IllegalStateException("database did not come back", last);
  }

  private Connection directConnection() throws SQLException {
    Properties p = new Properties();
    p.setProperty("user", db.getUsername());
    p.setProperty("password", db.getPassword());
    p.setProperty("connectTimeout", "3");
    p.setProperty("socketTimeout", "5");
    return DriverManager.getConnection(db.getJdbcUrl(), p);
  }

  private void awaitHeartbeatFailure() throws InterruptedException {
    long deadline = System.nanoTime() + LEASE.toNanos();
    while (System.nanoTime() < deadline) {
      if (child.output().contains("lease heartbeat failed")) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("no heartbeat failure logged during the outage:\n" + child.output());
  }

  // ---------------------------------------------------------------- inspection

  private Map<String, Object> dbState(String gen) throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    try (Connection c = directConnection()) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT g.status, g.last_ordinal, g.discard_reason,"
                  + " (SELECT count(*) FROM generation_entry e WHERE e.generation_id = g.id),"
                  + " (SELECT e.result FROM generation_entry e WHERE e.generation_id = g.id"
                  + "   ORDER BY e.ordinal DESC LIMIT 1),"
                  + " EXISTS (SELECT 1 FROM generation_output o WHERE o.generation_id = g.id),"
                  + " (SELECT c.name FROM generation c JOIN namespace n"
                  + "   ON n.current_generation_id = c.id WHERE n.name = g.namespace),"
                  + " (SELECT encode(sha256(string_agg(e.request || e.result, ''::bytea"
                  + "   ORDER BY e.ordinal)), 'hex') FROM generation_entry e"
                  + "   WHERE e.generation_id = g.id),"
                  + " g.lease_expires_at"
                  + " FROM generation g WHERE g.namespace = 'outage' AND g.name = ?")) {
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
          m.put("entry_sha256", rs.getString(8));
          Timestamp lease = rs.getTimestamp(9);
          m.put("lease_expires_at", lease == null ? null : lease.toString());
        }
      }
    }
    return m;
  }

  private Timestamp leaseExpiry(String gen) throws Exception {
    try (Connection c = directConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT lease_expires_at FROM generation"
                    + " WHERE namespace = 'outage' AND name = ?")) {
      ps.setString(1, gen);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        return rs.getTimestamp(1);
      }
    }
  }

  // ---------------------------------------------------------------- service calls

  private Map<String, Object> publishAndCompare(LeaseResponse lease, String gen) throws Exception {
    HttpResponse<String> p = publish(lease);
    assertEquals(200, p.statusCode(), p.body());
    Receipt receipt = Json.read(p.body().getBytes(StandardCharsets.UTF_8), Receipt.class);
    assertEquals("PUBLISHED", receipt.publicationStatus());
    BatchRunner.Output oracle = oracle();
    byte[] resout = bytes(NS + "/generations/" + gen + "/resout");
    byte[] polout = bytes(NS + "/generations/" + gen + "/polout");
    assertArrayEquals(oracle.resout(), resout);
    assertArrayEquals(oracle.polout(), polout);
    assertEquals(Sha256.of(resout), receipt.resoutSha256());
    assertEquals(gen, dbState(gen).get("current"));
    assertEquals(gen, node(get(NS + "/current").body()).get("current").asText());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("generation", gen);
    out.put("transactions_count", receipt.transactionsCount());
    out.put("resout_sha256", receipt.resoutSha256());
    out.put("polout_sha256", receipt.poloutSha256());
    out.put("matches_oracle", true);
    return out;
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
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(child.url(path)))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build(),
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
