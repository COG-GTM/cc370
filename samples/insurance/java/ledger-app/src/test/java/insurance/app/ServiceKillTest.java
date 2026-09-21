package insurance.app;

import static insurance.app.PostgresSupport.RATES_SHA256;
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
import insurance.app.http.Api.ApplyResponse;
import insurance.app.http.Api.BeginRequest;
import insurance.app.http.Api.ClaimRequest;
import insurance.app.http.Api.ClaimResponse;
import insurance.app.http.Api.DiscardAbandonedRequest;
import insurance.app.http.Api.FenceRequest;
import insurance.app.http.Api.ImportRequest;
import insurance.app.http.Api.LeaseResponse;
import insurance.app.http.Api.PublishRequest;
import insurance.app.http.Api.RawRequest;
import insurance.contract.v001.ContractV001;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.Json;
import insurance.ledger.PolicyLedgerService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * Real termination of the PostgreSQL-backed service and recovery by a replacement process. The HTTP
 * service runs in a child JVM ({@link ChildService}) whose {@code ledger.kill-switch} halts it
 * ({@code Runtime.halt(137)}: no shutdown hook, no transaction commit, no HTTP response) at a
 * defined boundary while a real HTTP client is waiting. The database is then inspected directly, a
 * second service process is started on the same database and, once the dead writer's lease has
 * lapsed, claims the same generation ({@code POST .../claim}): the store re-verifies the durable
 * prefix against the pinned contract, the client reconciles the committed request prefix with its
 * input ({@link PolicyLedgerService#resumeIndex}) and continues at {@code lastOrdinal + 1}. The
 * published bytes are compared with the pure INSBAT emulation and, for the corpus scenario, with
 * the archived MVS observation (A2, receipt-bound).
 *
 * <p>Also covered: a live (dead but unexpired) lease is never displaced; a writer frozen with
 * {@code SIGSTOP} that wakes after the takeover is fenced on every mutation; a corrupt prefix fails
 * the claim closed and the generation stays pending until an explicit discard; a published
 * generation cannot be claimed.
 *
 * <p>Bounded: durability across the halt is PostgreSQL's; no power-loss test; one host.
 */
class ServiceKillTest {
  private static final String NS = "/v1/namespaces/svc";
  private static final Duration LEASE = Duration.ofSeconds(2);
  private static final Path A2_STAGE =
      Path.of(System.getProperty("ledger.a2.stage", "../../evidence/28790e2/runtime/ifox-iewl/a"));
  private static final Path GOLDEN =
      Path.of(System.getProperty("ledger.golden", "../../golden/v1"));

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final RuntimeEvidence evidence =
      new RuntimeEvidence("insurance-java-service-kill-v2", "service-kill");
  private ChildService child;
  private ChildService second;

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

  private static ExpectedManifest stageManifest() {
    return manifest("a", 2, 4, seed(), txnin());
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
    if (second != null) {
      second.close();
    }
    if (child != null) {
      signal(child, "CONT");
      child.close();
    }
    evidence.write(info.getTestMethod().orElseThrow().getName());
  }

  // ---------------------------------------------------------------- scenarios

  @Test
  void killBeforeCommitThenTheReplacementClaimsAfterLeaseExpiryAndResumesAtOrdinal3()
      throws Exception {
    Map<String, Object> ev = evidence.section("before_commit_3");
    // a longer lease here: the replacement must come up while the dead writer's lease is live
    Duration lease6 = Duration.ofSeconds(6);
    child = ChildService.start(lease6, "before-commit:3");
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

    // the replacement starts at once: the dead writer's lease is still live, nothing may move
    child = ChildService.start(lease6, null);
    HttpResponse<String> early = claim("g1", stageManifest());
    assertEquals(409, early.statusCode(), early.body());
    assertTrue(early.body().contains("live writer"), early.body());
    assertEquals(409, raw(lease, requests().get(2)).statusCode(), "dead writer's fence");
    Map<String, Object> untouched = dbState("g1");
    assertEquals("PENDING", untouched.get("status"));
    assertEquals(2L, untouched.get("last_ordinal"));
    assertEquals(0L, untouched.get("claims"));
    ev.put("claim_while_lease_live", early.body());
    ev.put("db_untouched", untouched);

    Thread.sleep(lease6.toMillis() + 500);
    Map<String, Object> resumed = claimAndResume("g1", lease, 2);
    ev.put("resume", resumed);
    ev.put("db_after_resume", dbState("g1"));
    assertEquals(409, raw(lease, requests().get(3)).statusCode(), "old fence after takeover");
  }

  @Test
  void killAfterCommitLosesTheResponseAndTheResumeSkipsTheCommittedRequest() throws Exception {
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

    Thread.sleep(LEASE.toMillis() + 500);
    child = ChildService.start(LEASE, null);
    assertTrue(
        child.output().contains("abandoned pending generations left in place")
            && child.output().contains("svc/g1"),
        child.output());
    // the request whose response was lost is committed: the resume skips it (no DUPL appended)
    Map<String, Object> resumed = claimAndResume("g1", lease, 3);
    assertEquals(1, resumed.get("applied_on_resume"));
    ev.put("resume", resumed);
    ev.put("db_after_resume", dbState("g1"));
  }

  @Test
  void killInsidePublicationThenTheReplacementClaimsAndPublishesTheVerifiedPrefix()
      throws Exception {
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

    Thread.sleep(LEASE.toMillis() + 500);
    child = ChildService.start(LEASE, null);
    assertEquals(404, get(NS + "/generations/g1/receipt").statusCode());
    Map<String, Object> resumed = claimAndResume("g1", lease, 4);
    assertEquals(0, resumed.get("applied_on_resume"), "nothing left to apply, only to publish");
    ev.put("resume", resumed);
    ev.put("db_after_resume", dbState("g1"));
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

    Thread.sleep(LEASE.toMillis() + 500);
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
    HttpResponse<String> claimed = claim("g1", stageManifest());
    assertEquals(409, claimed.statusCode(), "a published generation is not claimable");
    ev.put("receipt_after_restart", receipt);
    ev.put("resout_matches_oracle", true);
    ev.put("claim_after_publication", claimed.body());
  }

  @Test
  void frozenWriterWakesAfterTheTakeoverAndEveryMutationIsFenced() throws Exception {
    Map<String, Object> ev = evidence.section("sigstop_writer_wakes");
    child = ChildService.start(LEASE, null);
    LeaseResponse old = importAndBegin("g1");
    apply(old, requests().get(0));
    apply(old, requests().get(1));
    signal(child, "STOP");
    ev.put("old_writer_pid", child.pid());
    ev.put("db_before_takeover", dbState("g1"));

    Thread.sleep(LEASE.toMillis() + 500);
    second = ChildService.start(LEASE, null);
    ChildService replacement = second;
    ClaimResponse c = claimOk(replacement, "g1", stageManifest());
    assertEquals(2, c.lastOrdinal());
    assertTrue(c.fence() > old.fence());
    LeaseResponse mine = new LeaseResponse(c.namespace(), c.generation(), c.parent(), c.fence());
    ApplyResponse third = applyOn(replacement, mine, requests().get(2));
    assertEquals(3, third.ordinal());
    Map<String, Object> afterClaim = dbState("g1");
    ev.put("claim", c);
    ev.put("db_after_claim", afterClaim);

    // the old process comes back to life: everything it tries with its lease is refused
    signal(child, "CONT");
    HttpResponse<String> write = raw(old, requests().get(3));
    HttpResponse<String> pub = publish(old);
    HttpResponse<String> disc = post(NS + "/generations/g1/discard", new FenceRequest(old.fence()));
    assertEquals(409, write.statusCode(), write.body());
    assertEquals(409, pub.statusCode(), pub.body());
    assertEquals(409, disc.statusCode(), disc.body());
    Thread.sleep(LEASE.toMillis());
    Map<String, Object> afterWake = dbState("g1");
    assertEquals("PENDING", afterWake.get("status"));
    assertEquals(3L, afterWake.get("last_ordinal"), "the old writer appended nothing");
    assertEquals(afterClaim.get("writer_id"), afterWake.get("writer_id"), "ownership kept");
    assertNotEquals(afterClaim.get("lease_expires_at"), afterWake.get("lease_expires_at"));
    assertTrue(
        ((String) afterWake.get("lease_expires_at"))
                .compareTo((String) afterClaim.get("lease_expires_at"))
            > 0,
        "only the claimant's heartbeat renews the lease");
    ev.put(
        "old_writer_after_wake",
        Map.of("raw", write.body(), "publish", pub.body(), "discard", disc.body()));
    ev.put("db_after_wake", afterWake);

    ApplyResponse fourth = applyOn(replacement, mine, requests().get(3));
    assertEquals(4, fourth.ordinal());
    ev.put("published", publishAndCompare(replacement, mine, oracle()));
    assertEquals(409, raw(old, requests().get(0)).statusCode(), "published: old lease still dead");
  }

  @Test
  void corruptPrefixFailsTheClaimClosedAndStaysPendingUntilExplicitDiscard() throws Exception {
    Map<String, Object> ev = evidence.section("corrupt_prefix_after_commit_3");
    child = ChildService.start(LEASE, "after-commit:3");
    LeaseResponse lease = importAndBegin("g1");
    apply(lease, requests().get(0));
    apply(lease, requests().get(1));
    assertThrows(IOException.class, () -> raw(lease, requests().get(2)));
    assertEquals(ChildService.HALTED, child.waitFor(), child.output());
    ev.put("db_after_kill", dbState("g1"));
    corruptResult("g1", 2);

    Thread.sleep(LEASE.toMillis() + 500);
    child = ChildService.start(LEASE, null);
    HttpResponse<String> claimed = claim("g1", stageManifest());
    assertEquals(409, claimed.statusCode(), claimed.body());
    assertTrue(claimed.body().contains("checkpoint"), claimed.body());
    Map<String, Object> db = dbState("g1");
    assertEquals("PENDING", db.get("status"), "not published, not discarded, not rerun");
    assertEquals(0L, db.get("claims"));
    assertEquals("root", db.get("current"));
    assertEquals(404, get(NS + "/generations/g1/receipt").statusCode());
    ev.put("claim_rejected", claimed.body());
    ev.put("db_after_rejected_claim", db);

    HttpResponse<String> again = claim("g1", stageManifest());
    assertEquals(409, again.statusCode(), "a repeated claim of a corrupt prefix fails the same");
    HttpResponse<String> discarded =
        post(
            NS + "/generations/g1/discard-abandoned",
            new DiscardAbandonedRequest("corrupt durable prefix"));
    assertEquals(200, discarded.statusCode(), discarded.body());
    Map<String, Object> after = dbState("g1");
    assertEquals("DISCARDED", after.get("status"));
    assertEquals(3L, after.get("entries"), "the evidence stays");
    assertEquals("root", after.get("current"));
    ev.put("db_after_discard", after);
    // a rerun is a new generation from the parent, reported as such
    LeaseResponse rerun = begin("g2");
    for (int i = 0; i < requests().size(); i++) {
      assertEquals(i + 1, apply(rerun, requests().get(i)).ordinal());
    }
    ev.put("rerun_g2", publishAndCompare(child, rerun, oracle()));
  }

  /**
   * The archived MVS observation for stage {@code a} (256 policies, 4,097 transactions): the
   * service is halted after committing ordinal 2,049 without answering, a replacement claims and
   * resumes at 2,050, and the published POLOUT / RESOUT must equal both the pure emulation and the
   * receipt-bound archived guest output.
   */
  @Test
  void killMidCorpusThenTheResumedGenerationEqualsTheArchivedMvsStage() throws Exception {
    Map<String, Object> ev = evidence.section("a2_stage_a_resume");
    A2Stage stage = A2Stage.load(A2_STAGE, GOLDEN);
    ev.put("authority", stage.provenance());
    List<TransactionRecord> input = Records.transactions(stage.txnin);
    assertEquals(4_097, input.size());
    int killAt = 2_049;
    ExpectedManifest manifest =
        manifest("a", stage.polin.length / 128, input.size(), stage.polin, stage.txnin);

    child = ChildService.start(LEASE, "after-commit:" + killAt);
    HttpResponse<String> imported =
        post(
            NS + "/import",
            new ImportRequest(
                "root",
                b64(stage.polin),
                b64(
                    Json.bytes(
                        manifest(
                            "bootstrap", stage.polin.length / 128, 0, stage.polin, new byte[0])))));
    assertEquals(201, imported.statusCode(), imported.body());
    HttpResponse<String> begun =
        post(
            NS + "/generations",
            new BeginRequest("root", "g1", b64(stage.polin), b64(Json.bytes(manifest))));
    assertEquals(201, begun.statusCode(), begun.body());
    LeaseResponse lease =
        Json.read(begun.body().getBytes(StandardCharsets.UTF_8), LeaseResponse.class);
    for (int i = 0; i < killAt - 1; i++) {
      assertEquals(i + 1, apply(lease, input.get(i)).ordinal());
    }
    assertThrows(IOException.class, () -> raw(lease, input.get(killAt - 1)), "response lost");
    assertEquals(ChildService.HALTED, child.waitFor(), child.output());
    ev.put("kill_line", killLine());
    Map<String, Object> db = dbState("g1");
    assertEquals((long) killAt, db.get("last_ordinal"));
    ev.put("db_after_kill", db);

    Thread.sleep(LEASE.toMillis() + 500);
    child = ChildService.start(LEASE, null);
    ClaimResponse c = claimOk(child, "g1", manifest);
    assertEquals(killAt, c.lastOrdinal());
    byte[] committed = bytes(NS + "/generations/g1/peek/requests");
    int next = PolicyLedgerService.resumeIndex(committed, input);
    assertEquals(killAt, next);
    assertEquals(Sha256.of(committed), c.committedRequestsSha256());
    LeaseResponse mine = new LeaseResponse(c.namespace(), c.generation(), c.parent(), c.fence());
    for (int i = next; i < input.size(); i++) {
      assertEquals(i + 1, applyOn(child, mine, input.get(i)).ordinal());
    }
    HttpResponse<String> p = publishOn(child, mine);
    assertEquals(200, p.statusCode(), p.body());
    Receipt receipt = Json.read(p.body().getBytes(StandardCharsets.UTF_8), Receipt.class);
    byte[] resout = bytes(NS + "/generations/g1/resout");
    byte[] polout = bytes(NS + "/generations/g1/polout");
    BatchRunner.Output oracle =
        new BatchRunner(ContractV001.frozen()).run(stage.polin, stage.txnin);
    assertArrayEquals(oracle.resout(), resout, "resumed RESOUT == uninterrupted emulation");
    assertArrayEquals(oracle.polout(), polout, "resumed POLOUT == uninterrupted emulation");
    assertArrayEquals(stage.resout, resout, "resumed RESOUT == archived MVS observation");
    assertArrayEquals(stage.polout, polout, "resumed POLOUT == archived MVS observation");
    assertEquals(stage.resoutSha256, receipt.resoutSha256());
    assertEquals(stage.poloutSha256, receipt.poloutSha256());
    assertEquals(input.size(), receipt.transactionsCount());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("claim", c);
    out.put("resumed_from_ordinal", next + 1);
    out.put("applied_on_resume", input.size() - next);
    out.put("resout_sha256", receipt.resoutSha256());
    out.put("polout_sha256", receipt.poloutSha256());
    out.put("equals_emulation", true);
    out.put("equals_archived_mvs", true);
    ev.put("resume", out);
    ev.put("db_after_resume", dbState("g1"));
  }

  // ---------------------------------------------------------------- recovery helpers

  /**
   * Claims {@code gen} on the running replacement, reconciles the committed request prefix with the
   * test input, applies the rest, publishes and compares with the emulation.
   */
  private Map<String, Object> claimAndResume(String gen, LeaseResponse old, int expectedOrdinal)
      throws Exception {
    ClaimResponse c = claimOk(child, gen, stageManifest());
    assertEquals(expectedOrdinal, c.lastOrdinal());
    assertEquals(1, c.claims());
    assertTrue(c.fence() > old.fence(), "fence moved forward");
    byte[] committed = bytes(NS + "/generations/" + gen + "/peek/requests");
    int next = PolicyLedgerService.resumeIndex(committed, requests());
    assertEquals(expectedOrdinal, next);
    assertEquals(Sha256.of(committed), c.committedRequestsSha256());
    LeaseResponse mine = new LeaseResponse(c.namespace(), c.generation(), c.parent(), c.fence());
    BatchRunner.Output oracle = oracle();
    for (int i = next; i < requests().size(); i++) {
      ApplyResponse a = applyOn(child, mine, requests().get(i));
      assertEquals(i + 1, a.ordinal());
      assertEquals(oracle.evaluations().get(i).result().status(), a.status());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("claim", c);
    out.put("resumed_from_ordinal", next + 1);
    out.put("applied_on_resume", requests().size() - next);
    out.putAll(publishAndCompare(child, mine, oracle));
    return out;
  }

  private Map<String, Object> publishAndCompare(
      ChildService svc, LeaseResponse lease, BatchRunner.Output oracle) throws Exception {
    HttpResponse<String> p = publishOn(svc, lease);
    assertEquals(200, p.statusCode(), p.body());
    Receipt receipt = Json.read(p.body().getBytes(StandardCharsets.UTF_8), Receipt.class);
    String gen = lease.generation();
    assertArrayEquals(oracle.resout(), bytesOn(svc, NS + "/generations/" + gen + "/resout"));
    assertArrayEquals(oracle.polout(), bytesOn(svc, NS + "/generations/" + gen + "/polout"));
    assertEquals(gen, dbState(gen).get("current"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("generation", gen);
    out.put("resout_sha256", receipt.resoutSha256());
    out.put("polout_sha256", receipt.poloutSha256());
    out.put("matches_oracle", true);
    return out;
  }

  private ClaimResponse claimOk(ChildService svc, String gen, ExpectedManifest manifest)
      throws Exception {
    HttpResponse<String> r = claimOn(svc, gen, manifest);
    assertEquals(200, r.statusCode(), r.body());
    return Json.read(r.body().getBytes(StandardCharsets.UTF_8), ClaimResponse.class);
  }

  private HttpResponse<String> claim(String gen, ExpectedManifest manifest) throws Exception {
    return claimOn(child, gen, manifest);
  }

  private HttpResponse<String> claimOn(ChildService svc, String gen, ExpectedManifest manifest)
      throws Exception {
    return postOn(
        svc, NS + "/generations/" + gen + "/claim", new ClaimRequest(b64(Json.bytes(manifest))));
  }

  private static void signal(ChildService svc, String sig) {
    if (!svc.isAlive()) {
      return;
    }
    try {
      new ProcessBuilder("kill", "-" + sig, Long.toString(svc.pid())).inheritIO().start().waitFor();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("kill -" + sig + " " + svc.pid(), e);
    }
  }

  private void corruptResult(String gen, int ordinal) throws Exception {
    try (Connection c = connect();
        Statement s = c.createStatement()) {
      s.execute("ALTER TABLE generation_entry DISABLE TRIGGER ALL");
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE generation_entry SET result = set_byte(result, 16, get_byte(result, 16) # 1)"
                  + " WHERE ordinal = ? AND generation_id ="
                  + " (SELECT id FROM generation WHERE namespace = 'svc' AND name = ?)")) {
        ps.setInt(1, ordinal);
        ps.setString(2, gen);
        assertEquals(1, ps.executeUpdate());
      } finally {
        s.execute("ALTER TABLE generation_entry ENABLE TRIGGER ALL");
      }
    }
  }

  /**
   * Archived A2 stage bytes, accepted only when they match the guest receipt they came with and
   * that receipt is bound to the pinned golden inputs ({@code polin.bin}, {@code a.txns.bin}) and
   * the frozen rate table the running contract carries ({@code rates.json}: file hash in the guest
   * receipt, canonical-row hash in the Java receipt).
   */
  private record A2Stage(
      byte[] polin,
      byte[] txnin,
      byte[] polout,
      byte[] resout,
      String resoutSha256,
      String poloutSha256,
      Map<String, Object> provenance) {
    static A2Stage load(Path dir, Path golden) throws IOException {
      assertTrue(Files.isDirectory(dir), "archived A2 stage missing: " + dir.toAbsolutePath());
      assertTrue(Files.isDirectory(golden), "golden inputs missing: " + golden.toAbsolutePath());
      JsonNode receipt = Json.read(Files.readAllBytes(dir.resolve("receipt.json")), JsonNode.class);
      assertEquals("insurance-run-v1", receipt.get("schema").asText());
      assertEquals("completed", receipt.get("outcome").asText());
      assertTrue(receipt.get("abend").isNull(), "ABEND");
      assertFalse(receipt.get("timed_out").asBoolean());
      assertEquals(0, receipt.get("step_rc").get("RUN").asInt());
      for (String k : List.of("build_manifest_sha256", "guest_manifest_sha256")) {
        assertTrue(receipt.get(k).asText().matches("[0-9a-f]{64}"), k);
      }
      byte[] ratesJson = Files.readAllBytes(golden.resolve("rates.json"));
      assertEquals(Sha256.of(ratesJson), receipt.get("rates_sha256").asText(), "guest rate file");
      assertEquals(RATES_SHA256, canonicalRateSha(ratesJson), "running contract's rate table");
      byte[] polin = Files.readAllBytes(dir.resolve("polin.bin"));
      byte[] txnin = Files.readAllBytes(dir.resolve("txnin.bin"));
      assertArrayEquals(Files.readAllBytes(golden.resolve("polin.bin")), polin, "pinned POLIN");
      assertArrayEquals(Files.readAllBytes(golden.resolve("a.txns.bin")), txnin, "pinned TXNIN");
      byte[] polout = Files.readAllBytes(dir.resolve("polout.bin"));
      byte[] resout = Files.readAllBytes(dir.resolve("resout.bin"));
      assertEquals(receipt.get("polin_sha256").asText(), Sha256.of(polin));
      assertEquals(receipt.get("txnin_sha256").asText(), Sha256.of(txnin));
      assertEquals(receipt.get("polout_sha256").asText(), Sha256.of(polout));
      assertEquals(receipt.get("resout_sha256").asText(), Sha256.of(resout));
      Map<String, Object> prov = new LinkedHashMap<>();
      prov.put("stage_dir", dir.normalize().toString());
      prov.put("golden_dir", golden.normalize().toString());
      prov.put("rate_table_canonical_sha256", RATES_SHA256);
      prov.put("job_id", receipt.get("job_id").asText());
      for (String k :
          List.of(
              "polin_sha256",
              "txnin_sha256",
              "polout_sha256",
              "resout_sha256",
              "build_manifest_sha256",
              "guest_manifest_sha256",
              "rates_sha256")) {
        prov.put(k, receipt.get(k).asText());
      }
      return new A2Stage(
          polin,
          txnin,
          polout,
          resout,
          receipt.get("resout_sha256").asText(),
          receipt.get("polout_sha256").asText(),
          prov);
    }

    /**
     * Same canonical form as the Java receipt and tools/parity_java.py: {@code e,r,f\n} per row.
     */
    static String canonicalRateSha(byte[] ratesJson) {
      StringBuilder sb = new StringBuilder();
      for (JsonNode row : Json.read(ratesJson, JsonNode.class)) {
        sb.append(row.get(0).asLong())
            .append(',')
            .append(row.get(1).asLong())
            .append(',')
            .append(row.get(2).asLong())
            .append('\n');
      }
      return Sha256.of(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }
  }

  // ---------------------------------------------------------------- inspection helpers

  private String killLine() {
    return child.output().lines().filter(l -> l.startsWith("kill switch")).findFirst().orElse("");
  }

  private static Connection connect() throws Exception {
    return DriverManager.getConnection(
        PostgresSupport.PG.getJdbcUrl(),
        PostgresSupport.PG.getUsername(),
        PostgresSupport.PG.getPassword());
  }

  private Map<String, Object> dbState(String gen) throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    try (Connection c = connect()) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT g.status, g.last_ordinal, g.discard_reason,"
                  + " (SELECT count(*) FROM generation_entry e WHERE e.generation_id = g.id),"
                  + " (SELECT e.result FROM generation_entry e WHERE e.generation_id = g.id"
                  + "   ORDER BY e.ordinal DESC LIMIT 1),"
                  + " EXISTS (SELECT 1 FROM generation_output o WHERE o.generation_id = g.id),"
                  + " (SELECT c.name FROM generation c JOIN namespace n"
                  + "   ON n.current_generation_id = c.id WHERE n.name = g.namespace),"
                  + " g.claims, g.fence, g.writer_id, g.lease_expires_at::text, g.checkpoint"
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
          m.put("claims", rs.getLong(8));
          m.put("fence", rs.getLong(9));
          m.put("writer_id", rs.getString(10));
          m.put("lease_expires_at", rs.getString(11));
          m.put("checkpoint", rs.getString(12));
        }
      }
    }
    return m;
  }

  // ---------------------------------------------------------------- HTTP helpers

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
    return applyOn(child, lease, t);
  }

  private ApplyResponse applyOn(ChildService svc, LeaseResponse lease, TransactionRecord t)
      throws Exception {
    HttpResponse<String> r = rawOn(svc, lease, t);
    assertEquals(200, r.statusCode(), r.body());
    return Json.read(r.body().getBytes(StandardCharsets.UTF_8), ApplyResponse.class);
  }

  private HttpResponse<String> raw(LeaseResponse lease, TransactionRecord t) throws Exception {
    return rawOn(child, lease, t);
  }

  private HttpResponse<String> rawOn(ChildService svc, LeaseResponse lease, TransactionRecord t)
      throws Exception {
    return postOn(
        svc,
        NS + "/generations/" + lease.generation() + "/requests:raw",
        new RawRequest(lease.fence(), Hex.of(t.bytes())));
  }

  private HttpResponse<String> publish(LeaseResponse lease) throws Exception {
    return publishOn(child, lease);
  }

  private HttpResponse<String> publishOn(ChildService svc, LeaseResponse lease) throws Exception {
    return postOn(
        svc,
        NS + "/generations/" + lease.generation() + "/publish",
        new PublishRequest(lease.fence(), "http"));
  }

  private HttpResponse<String> post(String path, Object body) throws Exception {
    return postOn(child, path, body);
  }

  private HttpResponse<String> postOn(ChildService svc, String path, Object body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(svc.url(path)))
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
    return bytesOn(child, path);
  }

  private byte[] bytesOn(ChildService svc, String path) throws Exception {
    HttpResponse<byte[]> r =
        client.send(
            HttpRequest.newBuilder(URI.create(svc.url(path))).GET().build(),
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
