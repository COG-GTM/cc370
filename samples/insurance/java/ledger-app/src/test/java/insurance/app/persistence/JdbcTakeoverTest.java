package insurance.app.persistence;

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

import insurance.app.PostgresSupport;
import insurance.contract.v001.ContractV001;
import insurance.contract.v001.Status;
import insurance.ledger.ContractBinding;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.PolicyLedgerService;
import insurance.ledger.Receipt;
import insurance.ledger.batch.BatchRunner;
import insurance.ledger.generation.GenerationStatus;
import insurance.ledger.generation.GenerationStore.CasException;
import insurance.ledger.generation.GenerationStore.CheckpointException;
import insurance.ledger.generation.GenerationStore.Claimed;
import insurance.ledger.generation.GenerationStore.FencedException;
import insurance.ledger.generation.GenerationStore.GenerationException;
import insurance.ledger.generation.GenerationStore.Lease;
import insurance.ledger.generation.PrefixVerifier;
import insurance.ledger.generation.ReceiptContext;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.TransactionRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writer takeover and verified-prefix resume on the PostgreSQL store. Every scenario runs with real
 * database time: a writer is "dead" when its lease has expired in the database, never because a
 * claimant believes so. Lease expiry is forced by moving {@code lease_expires_at} into the past
 * (the same row a dead heartbeat would have left behind); no store code path is bypassed.
 */
@SpringBootTest
class JdbcTakeoverTest {
  private static final String NS = "t";
  private static final ReceiptContext CTX =
      new ReceiptContext("http", "0000000", "jar:sha256:test", RATES_SHA256);
  private static final Duration LEASE = Duration.ofSeconds(30);

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    PostgresSupport.register(registry);
  }

  @Autowired DataSource dataSource;
  @Autowired JdbcClient db;
  @Autowired PlatformTransactionManager txManager;
  @Autowired GenerationRepository repo;

  private final List<JdbcGenerationStore> opened = new ArrayList<>();
  private JdbcGenerationStore a;
  private PolicyLedgerService svcA;

  @BeforeEach
  void reset() {
    PostgresSupport.resetSchema(dataSource);
    a = open();
    svcA = service(a);
    svcA.bootstrap(NS, "root", seed(), manifest("bootstrap", 2, 0, seed(), new byte[0]));
  }

  @AfterEach
  void closeStores() {
    for (JdbcGenerationStore s : opened) {
      s.close();
    }
  }

  private JdbcGenerationStore open() {
    return track(JdbcGenerationStore.open(db, new TransactionTemplate(txManager), repo, LEASE));
  }

  private JdbcGenerationStore open(ContractBinding binding) {
    return track(
        JdbcGenerationStore.open(
            db,
            new TransactionTemplate(txManager),
            repo,
            LEASE,
            JdbcGenerationStore.FaultPoint.NONE,
            binding));
  }

  private JdbcGenerationStore track(JdbcGenerationStore s) {
    opened.add(s);
    return s;
  }

  private static PolicyLedgerService service(JdbcGenerationStore s) {
    return new PolicyLedgerService(ContractV001.frozen(), s, CTX);
  }

  // ---------------------------------------------------------------- fixture

  private static byte[] seed() {
    return concat(a001().bytes(), fresh("00000002", 500_000, 20_000, 0).bytes());
  }

  /** Request 2 repeats request 1 (DUPL under the contract), so a replayed prefix would show. */
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

  private Lease begin(PolicyLedgerService svc, String gen) {
    return svc.begin(NS, "root", gen, Optional.of(seed()), stageManifest());
  }

  /** The writer commits {@code n} requests and then "dies": heartbeat gone, lease lapsed. */
  private Lease abandonedAfter(String gen, int n) {
    Lease lease = begin(svcA, gen);
    for (int i = 0; i < n; i++) {
      svcA.apply(lease, requests().get(i), false);
    }
    a.close();
    expire(gen);
    return lease;
  }

  private void expire(String gen) {
    db.sql(
            "UPDATE generation SET lease_expires_at = now() - interval '1 second'"
                + " WHERE namespace = ? AND name = ? AND status = 'PENDING'")
        .params(NS, gen)
        .update();
  }

  private void expireIn(String gen, String interval) {
    db.sql(
            "UPDATE generation SET lease_expires_at = now() + ?::interval"
                + " WHERE namespace = ? AND name = ? AND status = 'PENDING'")
        .params(interval, NS, gen)
        .update();
  }

  private long generationId(String gen) {
    return db.sql("SELECT id FROM generation WHERE namespace = ? AND name = ?")
        .params(NS, gen)
        .query(Long.class)
        .single();
  }

  private String column(String gen, String column) {
    return db.sql("SELECT " + column + "::text FROM generation WHERE namespace = ? AND name = ?")
        .params(NS, gen)
        .query(String.class)
        .single();
  }

  /** Claims, reconciles the committed prefix with the pinned input and finishes the generation. */
  private Receipt claimAndResume(JdbcGenerationStore s, String gen, int expectedResumeAt) {
    PolicyLedgerService svc = service(s);
    Claimed claimed = svc.claim(NS, gen, stageManifest());
    int next = PolicyLedgerService.resumeIndex(s.peekRequests(NS, gen), requests());
    assertEquals(expectedResumeAt, next);
    assertEquals(next, claimed.lastOrdinal());
    for (int i = next; i < requests().size(); i++) {
      assertEquals(i + 1, svc.apply(claimed.lease(), requests().get(i), false).ordinal());
    }
    return svc.publish(claimed.lease(), "http");
  }

  private void assertMatchesOracle(JdbcGenerationStore s, String gen, Receipt receipt) {
    BatchRunner.Output oracle = oracle();
    assertArrayEquals(oracle.resout(), s.resout(NS, gen));
    assertArrayEquals(oracle.polout(), s.polout(NS, gen));
    assertEquals(4, receipt.resultsCount());
    assertEquals(Optional.of(gen), s.current(NS));
  }

  // ---------------------------------------------------------------- takeover

  @Test
  void claimAfterLeaseExpiryVerifiesThePrefixAndResumesToTheUninterruptedOutput() {
    Lease dead = abandonedAfter("g1", 2);
    JdbcGenerationStore b = open();
    assertEquals(List.of(NS + "/g1"), b.abandonedOnOpen());

    Receipt receipt = claimAndResume(b, "g1", 2);
    assertMatchesOracle(b, "g1", receipt);

    List<JdbcGenerationStore.ClaimRow> claims = b.claims(NS, "g1");
    assertEquals(1, claims.size());
    assertEquals(dead.fence(), claims.get(0).oldFence());
    assertTrue(claims.get(0).newFence() > dead.fence());
    assertEquals(a.writerId(), claims.get(0).oldWriterId());
    assertEquals(b.writerId(), claims.get(0).newWriterId());
    assertEquals(2, claims.get(0).verifiedLastOrdinal());
    assertEquals("1", column("g1", "claims"));
    // nothing was processed twice: exactly four entries, the DUPL at ordinal 2 only
    assertEquals(
        4,
        db.sql("SELECT count(*) FROM generation_entry WHERE generation_id = ?")
            .param(generationId("g1"))
            .query(Integer.class)
            .single());
  }

  @Test
  void resumeAfterAnUnacknowledgedCommitDoesNotAppendTheLostRequestAgain() {
    // the third request committed but the writer died before answering (response lost)
    abandonedAfter("g1", 3);
    JdbcGenerationStore b = open();
    PolicyLedgerService svc = service(b);
    Claimed claimed = svc.claim(NS, "g1", stageManifest());
    assertEquals(3, claimed.lastOrdinal());
    // a naive client retrying request 3 would append DUPL; the reconciled index skips it
    assertEquals(3, PolicyLedgerService.resumeIndex(b.peekRequests(NS, "g1"), requests()));
    svc.apply(claimed.lease(), requests().get(3), false);
    Receipt receipt = svc.publish(claimed.lease(), "http");
    assertMatchesOracle(b, "g1", receipt);
  }

  @Test
  void claimWithNothingCommittedResumesFromTheSeed() {
    abandonedAfter("g1", 0);
    JdbcGenerationStore b = open();
    Receipt receipt = claimAndResume(b, "g1", 0);
    assertMatchesOracle(b, "g1", receipt);
  }

  @Test
  void liveWriterIsNeverDisplacedOrDiscarded() {
    Lease live = begin(svcA, "g1");
    svcA.apply(live, requests().get(0), false);
    JdbcGenerationStore b = open();
    assertEquals(List.of(NS + "/g1"), b.liveOnOpen());
    FencedException e =
        assertThrows(FencedException.class, () -> service(b).claim(NS, "g1", stageManifest()));
    assertTrue(e.getMessage().contains("live writer"), e.getMessage());
    assertThrows(
        FencedException.class, () -> service(b).discardAbandoned(NS, "g1", "not abandoned"));
    assertEquals("0", column("g1", "claims"));
    assertEquals(a.writerId(), column("g1", "writer_id"));
    // the live writer is unaffected
    assertEquals(2, svcA.apply(live, requests().get(1), false).ordinal());
  }

  @Test
  void expiredWriterCannotRenewCommitPublishOrDiscardEvenBeforeAnyClaim() {
    Lease dead = begin(svcA, "g1");
    svcA.apply(dead, requests().get(0), false);
    svcA.apply(dead, requests().get(1), false);
    svcA.apply(dead, requests().get(2), false);
    svcA.apply(dead, requests().get(3), false);
    expire("g1");
    String expiresAt = column("g1", "lease_expires_at");

    assertEquals(0, a.renewLeases(), "an expired lease is not resurrected");
    assertEquals(expiresAt, column("g1", "lease_expires_at"));
    assertThrows(FencedException.class, () -> svcA.apply(dead, requests().get(0), false));
    assertThrows(FencedException.class, () -> svcA.publish(dead, "http"));
    assertThrows(FencedException.class, () -> svcA.discard(dead));
    assertEquals(GenerationStatus.PENDING, a.info(NS, "g1").orElseThrow().status());
    assertEquals(4, a.info(NS, "g1").orElseThrow().lastOrdinal());
    assertEquals(Optional.of("root"), a.current(NS));
  }

  @Test
  void repeatedClaimsChainForwardAndEveryEarlierWriterStaysRejected() {
    Lease first = abandonedAfter("g1", 1);
    JdbcGenerationStore b = open();
    PolicyLedgerService svcB = service(b);
    Claimed second = svcB.claim(NS, "g1", stageManifest());
    svcB.apply(second.lease(), requests().get(1), false);
    // writer B dies too
    b.close();
    expire("g1");

    JdbcGenerationStore c = open();
    PolicyLedgerService svcC = service(c);
    Claimed third = svcC.claim(NS, "g1", stageManifest());
    assertEquals(2, third.lastOrdinal());
    assertEquals(2, third.claims());
    assertTrue(third.lease().fence() > second.lease().fence());
    assertTrue(second.lease().fence() > first.fence());

    // both earlier writers wake up: every mutation is fenced
    for (Lease stale : List.of(first, second.lease())) {
      assertThrows(FencedException.class, () -> svcA.apply(stale, requests().get(2), false));
      assertThrows(FencedException.class, () -> svcB.apply(stale, requests().get(2), false));
      assertThrows(FencedException.class, () -> svcA.publish(stale, "http"));
      assertThrows(FencedException.class, () -> svcB.publish(stale, "http"));
      assertThrows(FencedException.class, () -> svcA.discard(stale));
      assertThrows(FencedException.class, () -> svcB.discard(stale));
    }
    assertEquals(0, a.renewLeases());
    assertEquals(0, b.renewLeases());
    assertEquals(2, c.info(NS, "g1").orElseThrow().lastOrdinal(), "stale writers changed nothing");

    svcC.apply(third.lease(), requests().get(2), false);
    svcC.apply(third.lease(), requests().get(3), false);
    Receipt receipt = svcC.publish(third.lease(), "http");
    assertMatchesOracle(c, "g1", receipt);

    List<JdbcGenerationStore.ClaimRow> history = c.claims(NS, "g1");
    assertEquals(2, history.size());
    assertEquals(first.fence(), history.get(0).oldFence());
    assertEquals(second.lease().fence(), history.get(0).newFence());
    assertEquals(second.lease().fence(), history.get(1).oldFence());
    assertEquals(third.lease().fence(), history.get(1).newFence());
    assertEquals(1, history.get(0).verifiedLastOrdinal());
    assertEquals(2, history.get(1).verifiedLastOrdinal());
    // a published generation can no longer be claimed or discarded
    assertThrows(GenerationException.class, () -> svcC.claim(NS, "g1", stageManifest()));
    assertThrows(GenerationException.class, () -> svcC.discardAbandoned(NS, "g1", "late"));
  }

  @Test
  void concurrentClaimsHaveExactlyOneWinner() throws Exception {
    abandonedAfter("g1", 2);
    int n = 8;
    List<JdbcGenerationStore> stores = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      stores.add(open());
    }
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Claimed>> futures = new ArrayList<>();
    for (JdbcGenerationStore s : stores) {
      futures.add(
          pool.submit(
              () -> {
                go.await();
                return service(s).claim(NS, "g1", stageManifest());
              }));
    }
    go.countDown();
    List<Claimed> won = new ArrayList<>();
    int fenced = 0;
    for (Future<Claimed> f : futures) {
      try {
        won.add(f.get(60, TimeUnit.SECONDS));
      } catch (java.util.concurrent.ExecutionException e) {
        assertTrue(e.getCause() instanceof FencedException, String.valueOf(e.getCause()));
        fenced++;
      }
    }
    pool.shutdownNow();
    assertEquals(1, won.size(), "exactly one claimant owns the generation");
    assertEquals(n - 1, fenced);
    assertEquals("1", column("g1", "claims"));
    assertEquals(won.get(0).lease().fence(), Long.parseLong(column("g1", "fence")));
    assertEquals(1, stores.get(0).claims(NS, "g1").size());
    // the winner finishes the generation on any store that owns the winning writer id
    JdbcGenerationStore owner =
        stores.stream()
            .filter(s -> s.writerId().equals(column("g1", "writer_id")))
            .findFirst()
            .orElseThrow();
    PolicyLedgerService svc = service(owner);
    svc.apply(won.get(0).lease(), requests().get(2), false);
    svc.apply(won.get(0).lease(), requests().get(3), false);
    assertMatchesOracle(owner, "g1", svc.publish(won.get(0).lease(), "http"));
  }

  // ------------------------------------------------- claim versus commit / renew races

  /**
   * The old writer's commit and a claim wait on the same generation row lock, held here by a third
   * connection until the lease has lapsed in database time. Whatever order PostgreSQL wakes the
   * waiters, the expired commit must fail and the claim must win: the commit predicate is evaluated
   * against database time under the lock, not against the writer's belief.
   */
  @Test
  void claimVersusCommitRaceAcrossLeaseExpiryLetsOnlyTheClaimThrough() throws Exception {
    Lease old = begin(svcA, "g1");
    svcA.apply(old, requests().get(0), false);
    JdbcGenerationStore b = open();
    expireIn("g1", "400 milliseconds");
    RaceOutcome outcome =
        raceUnderRowLock(
            "g1",
            Duration.ofMillis(900),
            () -> svcA.apply(old, requests().get(1), false),
            () -> service(b).claim(NS, "g1", stageManifest()));
    assertTrue(
        outcome.commitFailed instanceof FencedException, String.valueOf(outcome.commitFailed));
    assertTrue(outcome.claimed != null, String.valueOf(outcome.claimFailed));
    assertEquals(1, outcome.claimed.lastOrdinal(), "the expired writer's commit did not land");
    assertEquals(1, b.info(NS, "g1").orElseThrow().lastOrdinal());
    assertEquals(b.writerId(), column("g1", "writer_id"));
  }

  /** The converse: the lease is still live when the lock is released, so the claim must lose. */
  @Test
  void claimVersusCommitRaceBeforeLeaseExpiryLetsOnlyTheCommitThrough() throws Exception {
    Lease old = begin(svcA, "g1");
    svcA.apply(old, requests().get(0), false);
    JdbcGenerationStore b = open();
    expireIn("g1", "20 seconds");
    RaceOutcome outcome =
        raceUnderRowLock(
            "g1",
            Duration.ofMillis(300),
            () -> svcA.apply(old, requests().get(1), false),
            () -> service(b).claim(NS, "g1", stageManifest()));
    assertTrue(outcome.commitFailed == null, String.valueOf(outcome.commitFailed));
    assertTrue(outcome.claimFailed instanceof FencedException, String.valueOf(outcome.claimFailed));
    assertEquals(2, a.info(NS, "g1").orElseThrow().lastOrdinal());
    assertEquals(a.writerId(), column("g1", "writer_id"));
    assertEquals("0", column("g1", "claims"));
  }

  @Test
  void claimVersusRenewRaceAcrossLeaseExpiryDoesNotResurrectTheOldLease() throws Exception {
    Lease old = begin(svcA, "g1");
    svcA.apply(old, requests().get(0), false);
    JdbcGenerationStore b = open();
    expireIn("g1", "400 milliseconds");
    RaceOutcome outcome =
        raceUnderRowLock(
            "g1",
            Duration.ofMillis(900),
            () -> {
              if (a.renewLeases() != 0) {
                throw new IllegalStateException("expired lease renewed");
              }
              return null;
            },
            () -> service(b).claim(NS, "g1", stageManifest()));
    assertTrue(outcome.commitFailed == null, String.valueOf(outcome.commitFailed));
    assertTrue(outcome.claimed != null, String.valueOf(outcome.claimFailed));
    assertEquals(b.writerId(), column("g1", "writer_id"));
    assertThrows(FencedException.class, () -> svcA.apply(old, requests().get(1), false));
  }

  private static final class RaceOutcome {
    Claimed claimed;
    Throwable claimFailed;
    Throwable commitFailed;
  }

  private interface Attempt {
    Object run();
  }

  private RaceOutcome raceUnderRowLock(
      String gen, Duration hold, Attempt oldWriter, Attempt claimant) throws Exception {
    long id = generationId(gen);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    RaceOutcome outcome = new RaceOutcome();
    try (Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try (PreparedStatement lock =
          blocker.prepareStatement("SELECT id FROM generation WHERE id = ? FOR UPDATE")) {
        lock.setLong(1, id);
        assertTrue(lock.executeQuery().next());
      }
      Future<Object> commit = pool.submit(oldWriter::run);
      Future<Object> claim = pool.submit(claimant::run);
      assertThrows(
          java.util.concurrent.TimeoutException.class,
          () -> commit.get(200, TimeUnit.MILLISECONDS),
          "the old writer must be blocked on the row lock");
      assertThrows(
          java.util.concurrent.TimeoutException.class,
          () -> claim.get(200, TimeUnit.MILLISECONDS),
          "the claimant must be blocked on the row lock");
      Thread.sleep(hold.toMillis());
      blocker.rollback();
      try {
        commit.get(60, TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException e) {
        outcome.commitFailed = e.getCause();
      }
      try {
        outcome.claimed = (Claimed) claim.get(60, TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException e) {
        outcome.claimFailed = e.getCause();
      }
    } finally {
      pool.shutdownNow();
    }
    return outcome;
  }

  // ---------------------------------------------------------------- CAS on resumed publication

  @Test
  void claimFailsWhenThePinnedParentIsNoLongerCurrent() {
    abandonedAfter("g1", 2);
    JdbcGenerationStore b = open();
    PolicyLedgerService svcB = service(b);
    // a sibling from the same parent publishes first
    Lease sibling = svcB.begin(NS, "root", "g2", Optional.of(seed()), stageManifest());
    for (TransactionRecord t : requests()) {
      svcB.apply(sibling, t, false);
    }
    svcB.publish(sibling, "http");
    assertThrows(CasException.class, () -> svcB.claim(NS, "g1", stageManifest()));
    assertEquals(GenerationStatus.PENDING, b.info(NS, "g1").orElseThrow().status());
    assertEquals("0", column("g1", "claims"));
    // explicit discard remains available and is a separate operation
    svcB.discardAbandoned(NS, "g1", "parent superseded");
    assertEquals(GenerationStatus.DISCARDED, b.info(NS, "g1").orElseThrow().status());
    assertTrue(column("g1", "discard_reason").endsWith("parent superseded"));
    assertEquals(2, b.info(NS, "g1").orElseThrow().lastOrdinal(), "evidence kept");
  }

  @Test
  void resumedPublicationKeepsExpectedParentCas() {
    abandonedAfter("g1", 2);
    JdbcGenerationStore b = open();
    PolicyLedgerService svcB = service(b);
    Claimed claimed = svcB.claim(NS, "g1", stageManifest());
    svcB.apply(claimed.lease(), requests().get(2), false);
    svcB.apply(claimed.lease(), requests().get(3), false);
    // current moves under the resumed writer
    JdbcGenerationStore c = open();
    PolicyLedgerService svcC = service(c);
    Lease sibling = svcC.begin(NS, "root", "g2", Optional.of(seed()), stageManifest());
    for (TransactionRecord t : requests()) {
      svcC.apply(sibling, t, false);
    }
    svcC.publish(sibling, "http");
    assertThrows(CasException.class, () -> svcB.publish(claimed.lease(), "http"));
    assertEquals(Optional.of("g2"), b.current(NS));
    assertEquals(GenerationStatus.DISCARDED, b.info(NS, "g1").orElseThrow().status());
    assertTrue(b.receipt(NS, "g1").isEmpty());
  }

  // ---------------------------------------------------------------- explicit discard

  @Test
  void discardAbandonedIsExplicitRequiresAnExpiredLeaseAndKeepsTheEvidence() {
    Lease live = begin(svcA, "g1");
    svcA.apply(live, requests().get(0), false);
    assertThrows(FencedException.class, () -> svcA.discardAbandoned(NS, "g1", "too early"));
    a.close();
    expire("g1");
    JdbcGenerationStore b = open();
    service(b).discardAbandoned(NS, "g1", "operator");
    assertEquals(GenerationStatus.DISCARDED, b.info(NS, "g1").orElseThrow().status());
    assertEquals(1, b.info(NS, "g1").orElseThrow().lastOrdinal());
    assertTrue(column("g1", "discard_reason").endsWith("explicitly: operator"));
    assertThrows(GenerationException.class, () -> service(b).claim(NS, "g1", stageManifest()));
    assertThrows(GenerationException.class, () -> service(b).discardAbandoned(NS, "g1", "again"));
    assertThrows(FencedException.class, () -> svcA.apply(live, requests().get(1), false));
  }

  // ---------------------------------------------------------------- fail-closed controls

  private void corrupt(String table, String sql, Object... params) {
    db.sql("ALTER TABLE " + table + " DISABLE TRIGGER ALL").update();
    try {
      db.sql(sql).params(params).update();
    } finally {
      db.sql("ALTER TABLE " + table + " ENABLE TRIGGER ALL").update();
    }
  }

  private void assertClaimFailsClosed(
      Class<? extends RuntimeException> expected, ExpectedManifest manifest) {
    assertClaimFailsClosed(open(), expected, manifest);
  }

  private void assertClaimFailsClosed(
      JdbcGenerationStore b,
      Class<? extends RuntimeException> expected,
      ExpectedManifest manifest) {
    PolicyLedgerService svcB = service(b);
    assertThrows(expected, () -> svcB.claim(NS, "g1", manifest));
    // nothing was taken over, published or rerun
    assertEquals(GenerationStatus.PENDING, b.info(NS, "g1").orElseThrow().status());
    assertEquals("0", column("g1", "claims"));
    assertEquals(a.writerId(), column("g1", "writer_id"));
    assertEquals(Optional.of("root"), b.current(NS));
    assertTrue(b.claims(NS, "g1").isEmpty());
    // the explicit discard is still available to an operator
    svcB.discardAbandoned(NS, "g1", "control");
    assertEquals(GenerationStatus.DISCARDED, b.info(NS, "g1").orElseThrow().status());
  }

  @Test
  void corruptResultBytesFailTheClaim() {
    abandonedAfter("g1", 2);
    long id = generationId("g1");
    corrupt(
        "generation_entry",
        "UPDATE generation_entry SET result = set_byte(result, 16, get_byte(result, 16) # 1)"
            + " WHERE generation_id = ? AND ordinal = 1",
        id);
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  @Test
  void corruptSuccessorBytesFailTheClaim() {
    abandonedAfter("g1", 2);
    long id = generationId("g1");
    corrupt(
        "generation_entry",
        "UPDATE generation_entry SET successor ="
            + " set_byte(successor, 40, get_byte(successor, 40) # 1)"
            + " WHERE generation_id = ? AND ordinal = 1 AND successor IS NOT NULL",
        id);
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  @Test
  void corruptPolicyStateFailsTheClaim() {
    abandonedAfter("g1", 2);
    long id = generationId("g1");
    corrupt(
        "policy_state",
        "UPDATE policy_state SET bytes = set_byte(bytes, 100, get_byte(bytes, 100) # 1)"
            + " WHERE generation_id = ? AND position = 0",
        id);
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  @Test
  void ordinalGapFailsTheClaim() {
    abandonedAfter("g1", 3);
    long id = generationId("g1");
    corrupt(
        "generation_entry",
        "DELETE FROM generation_entry WHERE generation_id = ? AND ordinal = 2",
        id);
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  @Test
  void tamperedCheckpointFailsTheClaim() {
    abandonedAfter("g1", 2);
    long id = generationId("g1");
    corrupt("generation", "UPDATE generation SET checkpoint = repeat('0', 64) WHERE id = ?", id);
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  // ------------------------------------------------- typed/raw admission metadata (R1)

  /** Request 1 typed, request 2 raw, then the writer dies: counters 1/1. */
  private void abandonedMixedAfterTwo() {
    Lease lease = begin(svcA, "g1");
    svcA.apply(lease, requests().get(0), true);
    svcA.apply(lease, requests().get(1), false);
    a.close();
    expire("g1");
    assertEquals("1", column("g1", "typed_requests"));
    assertEquals("1", column("g1", "raw_requests"));
  }

  @Test
  void flippedEntryTypedFlagBreaksTheChainAndFailsTheClaim() {
    abandonedMixedAfterTwo();
    long id = generationId("g1");
    corrupt(
        "generation_entry",
        "UPDATE generation_entry SET typed = NOT typed WHERE generation_id = ? AND ordinal = 1",
        id);
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  @Test
  void flippedTypedFlagWithAReforgedChainStillFailsOnTheCounters() {
    abandonedMixedAfterTwo();
    long id = generationId("g1");
    // Flip entry 1 to raw and re-derive a chain that is internally consistent with the flipped
    // flag, request/result/successor bytes untouched; only the row counters now disagree.
    List<Object[]> rows =
        db.sql(
                "SELECT ordinal, request, result, successor FROM generation_entry"
                    + " WHERE generation_id = ? ORDER BY ordinal")
            .param(id)
            .query(
                (rs, i) ->
                    new Object[] {
                      rs.getLong("ordinal"),
                      rs.getBytes("request"),
                      rs.getBytes("result"),
                      rs.getBytes("successor")
                    })
            .list();
    String chain = PrefixVerifier.seedChain(seed());
    for (Object[] r : rows) {
      long ordinal = (Long) r[0];
      boolean typed = false; // every entry raw after the flip
      chain =
          PrefixVerifier.chain(chain, ordinal, (byte[]) r[1], (byte[]) r[2], typed, (byte[]) r[3]);
      corrupt(
          "generation_entry",
          "UPDATE generation_entry SET typed = ?, chain = ?"
              + " WHERE generation_id = ? AND ordinal = ?",
          typed,
          chain,
          id,
          ordinal);
    }
    corrupt("generation", "UPDATE generation SET checkpoint = ? WHERE id = ?", chain, id);
    CheckpointException e =
        assertThrows(
            CheckpointException.class, () -> service(open()).claim(NS, "g1", stageManifest()));
    assertTrue(e.getMessage().contains("typed-request counter"), e.getMessage());
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  @Test
  void wrongTypedOrRawCounterFailsTheClaimWithIntactEntries() {
    abandonedMixedAfterTwo();
    long id = generationId("g1");
    // counters swapped: sum still equals last_ordinal, entries and chain untouched
    corrupt(
        "generation",
        "UPDATE generation SET typed_requests = 0, raw_requests = 2 WHERE id = ?",
        id);
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  @Test
  void countersNotAccountingForEveryEntryFailTheClaim() {
    abandonedMixedAfterTwo();
    long id = generationId("g1");
    corrupt("generation", "UPDATE generation SET raw_requests = 0 WHERE id = ?", id);
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  @Test
  void claimReportsTheVerifiedTypedAndRawCounts() {
    abandonedMixedAfterTwo();
    Claimed claimed = service(open()).claim(NS, "g1", stageManifest());
    assertEquals(2, claimed.lastOrdinal());
    assertEquals(1, claimed.typedRequests());
    assertEquals(1, claimed.rawRequests());
  }

  // ------------------------------------------------- mandatory V3 metadata (R2)

  @Test
  void schemaRefusesMissingCheckpointMetadata() {
    abandonedAfter("g1", 2);
    long id = generationId("g1");
    for (String sql :
        List.of(
            "UPDATE generation SET checkpoint = NULL WHERE id = ?",
            "UPDATE generation SET contract_identity = NULL WHERE id = ?",
            "UPDATE generation_entry SET chain = NULL WHERE generation_id = ? AND ordinal = 1")) {
      String table = sql.contains("generation_entry") ? "generation_entry" : "generation";
      assertThrows(
          org.springframework.dao.DataAccessException.class, () -> corrupt(table, sql, id), sql);
    }
    assertEquals(2, service(open()).claim(NS, "g1", stageManifest()).lastOrdinal());
  }

  @Test
  void missingGenerationCheckpointFailsClosedEvenIfTheSchemaAllowedIt() {
    abandonedAfter("g1", 2);
    long id = generationId("g1");
    db.sql("ALTER TABLE generation ALTER COLUMN checkpoint DROP NOT NULL").update();
    corrupt("generation", "UPDATE generation SET checkpoint = NULL WHERE id = ?", id);
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  @Test
  void missingContractIdentityFailsClosedEvenIfTheSchemaAllowedIt() {
    abandonedAfter("g1", 2);
    long id = generationId("g1");
    db.sql("ALTER TABLE generation ALTER COLUMN contract_identity DROP NOT NULL").update();
    corrupt("generation", "UPDATE generation SET contract_identity = NULL WHERE id = ?", id);
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  @Test
  void missingEntryChainFailsClosedEvenIfTheSchemaAllowedIt() {
    abandonedAfter("g1", 2);
    long id = generationId("g1");
    db.sql("ALTER TABLE generation_entry ALTER COLUMN chain DROP NOT NULL").update();
    corrupt(
        "generation_entry",
        "UPDATE generation_entry SET chain = NULL WHERE generation_id = ? AND ordinal = 2",
        id);
    CheckpointException e =
        assertThrows(
            CheckpointException.class, () -> service(open()).claim(NS, "g1", stageManifest()));
    assertTrue(e.getMessage().contains("no chain value"), e.getMessage());
    assertClaimFailsClosed(CheckpointException.class, stageManifest());
  }

  // ------------------------------------------------- lost claim response (R3)

  @Test
  void lostClaimResponseIsReconciledByTheSameReplacementWriterWithoutASecondTransition() {
    abandonedAfter("g1", 2);
    JdbcGenerationStore b = open();
    PolicyLedgerService svcB = service(b);
    Claimed first = svcB.claim(NS, "g1", stageManifest());
    // the claim committed but B never saw the answer: B simply asks again
    String leaseBefore = column("g1", "lease_expires_at");
    Claimed retry = svcB.claim(NS, "g1", stageManifest());
    assertEquals(first, retry, "same fence, claim number, prefix and checkpoint");
    assertEquals("1", column("g1", "claims"));
    assertEquals(String.valueOf(first.lease().fence()), column("g1", "fence"));
    assertEquals(b.writerId(), column("g1", "writer_id"));
    assertEquals(1, b.claims(NS, "g1").size(), "no second claim-history row");
    assertEquals(leaseBefore, column("g1", "lease_expires_at"), "reconciliation renews nothing");
    // a third writer is still refused while B's lease is live
    JdbcGenerationStore c = open();
    FencedException e =
        assertThrows(FencedException.class, () -> service(c).claim(NS, "g1", stageManifest()));
    assertTrue(e.getMessage().contains("live writer"), e.getMessage());
    // and B continues from the reconciled fence to the uninterrupted output
    int next = PolicyLedgerService.resumeIndex(b.peekRequests(NS, "g1"), requests());
    assertEquals(2, next);
    for (int i = next; i < requests().size(); i++) {
      assertEquals(i + 1, svcB.apply(retry.lease(), requests().get(i), false).ordinal());
    }
    assertMatchesOracle(b, "g1", svcB.publish(retry.lease(), "http"));
  }

  @Test
  void reconciliationStillVerifiesThePrefixAndThePinnedManifest() {
    abandonedAfter("g1", 2);
    JdbcGenerationStore b = open();
    PolicyLedgerService svcB = service(b);
    Claimed first = svcB.claim(NS, "g1", stageManifest());
    assertThrows(
        CheckpointException.class,
        () -> svcB.claim(NS, "g1", manifest("b", 2, 4, seed(), txnin())));
    long id = generationId("g1");
    corrupt(
        "generation_entry",
        "UPDATE generation_entry SET result = set_byte(result, 16, get_byte(result, 16) # 1)"
            + " WHERE generation_id = ? AND ordinal = 1",
        id);
    assertThrows(CheckpointException.class, () -> svcB.claim(NS, "g1", stageManifest()));
    assertEquals(String.valueOf(first.lease().fence()), column("g1", "fence"));
    assertEquals("1", column("g1", "claims"));
  }

  @Test
  void changedPinnedInputOrManifestFailsTheClaim() {
    abandonedAfter("g1", 2);
    byte[] other = Records.join(requests().subList(0, 3));
    assertClaimFailsClosed(CheckpointException.class, manifest("a", 2, 3, seed(), other));
  }

  @Test
  void changedRateTableFailsTheClaim() {
    abandonedAfter("g1", 2);
    String otherRates = "f".repeat(64);
    ContractBinding relabelled =
        ContractBinding.relabelled(
            ContractV001.frozen(),
            otherRates,
            ContractBinding.of(ContractV001.frozen()).identity());
    assertClaimFailsClosed(
        open(relabelled), ExpectedManifest.ManifestException.class, stageManifest());
  }

  @Test
  void changedContractImplementationIdentityFailsTheClaim() {
    abandonedAfter("g1", 2);
    ContractBinding relabelled =
        ContractBinding.relabelled(
            ContractV001.frozen(), RATES_SHA256, ContractBinding.IDENTITY_PREFIX + "0".repeat(64));
    assertClaimFailsClosed(open(relabelled), CheckpointException.class, stageManifest());
  }

  @Test
  void identityIsContentDerivedAndPinnedImmutably() {
    ContractBinding binding = ContractBinding.of(ContractV001.frozen());
    assertTrue(binding.identity().startsWith(ContractBinding.IDENTITY_PREFIX));
    assertEquals(binding.identity(), ContractBinding.of(ContractV001.frozen()).identity());
    assertNotEquals(
        binding.identity(),
        ContractBinding.relabelled(ContractV001.frozen(), "0".repeat(64), "x").identity());
    begin(svcA, "g1");
    assertEquals(binding.identity(), column("g1", "contract_identity"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.sql("UPDATE generation SET contract_identity = 'x' WHERE name = 'g1'").update(),
        "the guard keeps the pinned identity immutable");
    assertFalse(column("g1", "checkpoint").isEmpty());
  }

  @Test
  void entryStateAndCheckpointAreOneTransaction() {
    Lease lease = begin(svcA, "g1");
    svcA.apply(lease, requests().get(0), false);
    String before = column("g1", "checkpoint");
    RuntimeException boom = new RuntimeException("after entry, before commit");
    assertThrows(
        RuntimeException.class,
        () ->
            a.commit(
                lease,
                requests().get(2),
                m -> {
                  throw boom;
                },
                false));
    assertEquals(before, column("g1", "checkpoint"));
    assertEquals(1, a.info(NS, "g1").orElseThrow().lastOrdinal());
    assertEquals(
        1,
        db.sql("SELECT count(*) FROM generation_entry WHERE generation_id = ?")
            .param(generationId("g1"))
            .query(Integer.class)
            .single());
    // the retried request is the next ordinal with the expected status
    assertEquals(Status.OKAY, svcA.apply(lease, requests().get(2), false).evaluation().status());
    assertNotEquals(before, column("g1", "checkpoint"));
  }
}
