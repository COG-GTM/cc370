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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import insurance.app.PostgresSupport;
import insurance.contract.v001.ContractV001;
import insurance.contract.v001.Status;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.PolicyLedgerService;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.ledger.generation.GenerationStatus;
import insurance.ledger.generation.GenerationStore.Applied;
import insurance.ledger.generation.GenerationStore.CasException;
import insurance.ledger.generation.GenerationStore.FencedException;
import insurance.ledger.generation.GenerationStore.GenerationException;
import insurance.ledger.generation.GenerationStore.Lease;
import insurance.ledger.generation.ReceiptContext;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class JdbcGenerationStoreTest {
  private static final String NS = "t";
  private static final ReceiptContext CTX =
      new ReceiptContext("http", "0000000", "jar:sha256:test", RATES_SHA256);

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    PostgresSupport.register(registry);
  }

  @Autowired DataSource dataSource;
  @Autowired JdbcClient db;
  @Autowired PlatformTransactionManager txManager;
  @Autowired GenerationRepository repo;

  private JdbcGenerationStore store;
  private PolicyLedgerService svc;

  @BeforeEach
  void reset() {
    PostgresSupport.resetSchema(dataSource);
    store = open();
    svc = service(store);
  }

  @AfterEach
  void closeStore() {
    store.close();
  }

  private JdbcGenerationStore open() {
    return JdbcGenerationStore.open(db, new TransactionTemplate(txManager), repo);
  }

  private static PolicyLedgerService service(JdbcGenerationStore s) {
    return new PolicyLedgerService(ContractV001.frozen(), s, CTX);
  }

  private static byte[] seed() {
    return concat(a001().bytes(), fresh("00000002", 500_000, 20_000, 0).bytes());
  }

  private static List<TransactionRecord> requests() {
    return List.of(
        txn("00000001", 1, VALUATION, 'P', 10_000),
        txn("00000001", 1, VALUATION, 'P', 10_000),
        txn("00000002", 1, VALUATION, 'W', 5_000));
  }

  private static byte[] txnin() {
    return Records.join(requests());
  }

  private void bootstrap() {
    svc.bootstrap(NS, "root", seed(), manifest("bootstrap", 2, 0, seed(), new byte[0]));
  }

  private static ExpectedManifest stageManifest() {
    return manifest("a", 2, 3, seed(), txnin());
  }

  private Lease begin(String parent, String gen, ExpectedManifest manifest) {
    return svc.begin(NS, parent, gen, Optional.of(seed()), manifest);
  }

  private Receipt runGeneration(String parent, String gen) {
    Lease lease = begin(parent, gen, stageManifest());
    for (TransactionRecord t : requests()) {
      svc.apply(lease, t, false);
    }
    return svc.publish(lease, "http");
  }

  private long generationId(String gen) {
    return db.sql("SELECT id FROM generation WHERE namespace = ? AND name = ?")
        .params(NS, gen)
        .query(Long.class)
        .single();
  }

  // ---------------------------------------------------------------- lifecycle

  @Test
  void bootstrapPublishesRootAndRefusesSecondRoot() {
    bootstrap();
    assertEquals(Optional.of("root"), store.current(NS));
    assertArrayEquals(seed(), store.polout(NS, "root"));
    assertEquals(0, store.resout(NS, "root").length);
    assertEquals(GenerationStatus.PUBLISHED, store.info(NS, "root").orElseThrow().status());
    assertThrows(
        CasException.class,
        () ->
            store.bootstrap(
                NS,
                "root2",
                List.of(a001()),
                manifest("bootstrap", 1, 0, a001().bytes(), new byte[0])));
  }

  @Test
  void applyPublishChainAndReceiptHashesMatchStoredBytes() {
    bootstrap();
    Receipt receipt = runGeneration("root", "g1");
    assertEquals("root", receipt.parentGeneration());
    assertEquals(3, receipt.resultsCount());
    assertEquals(3, receipt.rawRequests());
    assertEquals(Sha256.of(txnin()), receipt.txninSha256());
    assertEquals(Sha256.of(store.resout(NS, "g1")), receipt.resoutSha256());
    assertEquals(Sha256.of(store.polout(NS, "g1")), receipt.poloutSha256());
    assertArrayEquals(txnin(), store.requests(NS, "g1"));
    assertEquals(Optional.of("g1"), store.current(NS));
    assertEquals(
        List.of("OKAY", "DUPL", "OKAY"),
        Records.results(store.resout(NS, "g1")).stream().map(ResultRecord::status).toList());
    byte[] g1 = store.polout(NS, "g1");
    assertFalse(java.util.Arrays.equals(seed(), g1));
    // successors are seeded from the pinned published parent, and its bytes must match POLIN
    assertThrows(
        GenerationException.class,
        () -> svc.begin(NS, "g1", "g2", Optional.of(seed()), manifest("b", 2, 1, g1, txnin())));
    Lease g2 = svc.begin(NS, "g1", "g2", Optional.of(g1), manifest("b", 2, 1, g1, txnin()));
    Applied applied = svc.apply(g2, txn("00000001", 1, VALUATION, 'P', 1), false);
    assertEquals(Status.CNFL, applied.evaluation().status());
    assertEquals(1, applied.ordinal());
    assertEquals(Optional.of(receipt), store.receipt(NS, "g1"));
  }

  @Test
  void emptyTxninPublishesUnchangedMasterAndZeroResults() {
    bootstrap();
    Lease lease = begin("root", "g1", manifest("empty", 2, 0, seed(), new byte[0]));
    Receipt r = svc.publish(lease, "http");
    assertEquals(0, r.resultsCount());
    assertArrayEquals(seed(), store.polout(NS, "g1"));
    assertEquals(0, store.resout(NS, "g1").length);
  }

  @Test
  void publishValidatesAgainstPinnedManifestAndDiscardsOnFailure() {
    bootstrap();
    // the manifest pinned at begin expects two requests; three arrive (an unexpectedly long input)
    Lease lease = begin("root", "g1", manifest("a", 2, 2, seed(), txnin()));
    for (TransactionRecord t : requests()) {
      svc.apply(lease, t, false);
    }
    assertEquals(
        Optional.of(manifest("a", 2, 2, seed(), txnin())),
        store.manifest(NS, "g1").map(m -> m.withSourceSha256(null)));
    assertThrows(ExpectedManifest.ManifestException.class, () -> svc.publish(lease, "http"));
    assertEquals(GenerationStatus.DISCARDED, store.info(NS, "g1").orElseThrow().status());
    assertThrows(GenerationException.class, () -> svc.apply(lease, requests().get(0), false));
    assertThrows(GenerationException.class, () -> store.polout(NS, "g1"));
    assertEquals(Optional.of("root"), store.current(NS));
  }

  @Test
  void wrongTxninHashIsRejectedEvenWhenCountsMatch() {
    bootstrap();
    byte[] other = txnin();
    other[39] ^= 0x01;
    Lease lease = begin("root", "g1", manifest("a", 2, 3, seed(), other));
    for (TransactionRecord t : requests()) {
      svc.apply(lease, t, false);
    }
    assertThrows(ExpectedManifest.ManifestException.class, () -> svc.publish(lease, "http"));
    assertEquals(Optional.of("root"), store.current(NS));
    assertEquals(GenerationStatus.DISCARDED, store.info(NS, "g1").orElseThrow().status());
  }

  @Test
  void manifestIsCheckedBeforeAnyRequestIsAccepted() {
    bootstrap();
    ExpectedManifest good = stageManifest();
    // unsupported schema
    assertThrows(
        ExpectedManifest.ManifestException.class,
        () ->
            begin(
                "root",
                "g1",
                new ExpectedManifest(
                    "insurance-expected-manifest-v0",
                    good.stage(),
                    good.policiesCount(),
                    good.transactionsCount(),
                    good.polinSha256(),
                    good.txninSha256(),
                    good.ratesSha256(),
                    null)));
    // rate table the running contract does not use
    assertThrows(
        ExpectedManifest.ManifestException.class,
        () ->
            begin(
                "root",
                "g1",
                new ExpectedManifest(
                    good.schema(),
                    good.stage(),
                    good.policiesCount(),
                    good.transactionsCount(),
                    good.polinSha256(),
                    good.txninSha256(),
                    Sha256.of(new byte[] {1}),
                    null)));
    // missing rate binding
    assertThrows(
        ExpectedManifest.ManifestException.class,
        () ->
            begin(
                "root",
                "g1",
                new ExpectedManifest(
                    good.schema(),
                    good.stage(),
                    good.policiesCount(),
                    good.transactionsCount(),
                    good.polinSha256(),
                    good.txninSha256(),
                    null,
                    null)));
    // POLIN hash / count that do not describe the pinned parent
    byte[] otherSeed = seed();
    otherSeed[20] ^= 0x01;
    assertThrows(
        ExpectedManifest.ManifestException.class,
        () -> begin("root", "g1", manifest("a", 2, 3, otherSeed, txnin())));
    assertThrows(
        ExpectedManifest.ManifestException.class,
        () -> begin("root", "g1", manifest("a", 1, 3, seed(), txnin())));
    // nothing was created by any of the refusals
    assertTrue(store.info(NS, "g1").isEmpty());
    // bootstrap enforces the same binding
    assertThrows(
        ExpectedManifest.ManifestException.class,
        () ->
            svc.bootstrap(
                "n2",
                "root",
                seed(),
                new ExpectedManifest(
                    good.schema(),
                    "bootstrap",
                    2,
                    0,
                    Sha256.of(seed()),
                    Sha256.of(new byte[0]),
                    null,
                    null)));
    assertTrue(store.info("n2", "root").isEmpty());
  }

  @Test
  void publicationIsBoundToThePinnedManifestNotToWhatArrived() {
    bootstrap();
    // manifest pinned for three specific requests; only two arrive (aligned truncation)
    Lease lease = begin("root", "g1", stageManifest());
    svc.apply(lease, requests().get(0), false);
    svc.apply(lease, requests().get(1), false);
    assertThrows(ExpectedManifest.ManifestException.class, () -> svc.publish(lease, "http"));
    assertEquals(GenerationStatus.DISCARDED, store.info(NS, "g1").orElseThrow().status());
    // genuinely empty input is distinguishable: an empty manifest publishes an empty generation
    Lease empty = begin("root", "g2", manifest("empty", 2, 0, seed(), new byte[0]));
    assertEquals(0, svc.publish(empty, "http").resultsCount());
    // ... whereas an empty arrival against a non-empty manifest is refused
    Lease missing = begin("g2", "g3", manifest("a", 2, 3, seed(), txnin()));
    assertThrows(ExpectedManifest.ManifestException.class, () -> svc.publish(missing, "http"));
  }

  // ---------------------------------------------------------------- fencing and CAS

  @Test
  void oneWriterPerGenerationAndStaleFencesAreRejected() {
    bootstrap();
    Lease first = begin("root", "g1", stageManifest());
    assertThrows(GenerationException.class, () -> begin("root", "g1", stageManifest()));
    svc.discard(first);
    assertThrows(FencedException.class, () -> svc.apply(first, requests().get(0), false));
    Lease again = begin("root", "g1b", stageManifest());
    Lease forged = new Lease(NS, "g1b", "root", again.fence() + 1);
    assertThrows(FencedException.class, () -> svc.apply(forged, requests().get(0), false));
    assertThrows(FencedException.class, () -> svc.publish(forged, "http"));
    assertEquals(GenerationStatus.PENDING, store.info(NS, "g1b").orElseThrow().status());
  }

  @Test
  void publishCasFailsWhenCurrentMovedUnderTheLease() {
    bootstrap();
    Lease slow = begin("root", "slow", stageManifest());
    runGeneration("root", "fast");
    for (TransactionRecord t : requests()) {
      svc.apply(slow, t, false);
    }
    assertThrows(CasException.class, () -> svc.publish(slow, "http"));
    assertEquals(Optional.of("fast"), store.current(NS));
    assertEquals(GenerationStatus.DISCARDED, store.info(NS, "slow").orElseThrow().status());
    assertThrows(GenerationException.class, () -> store.polout(NS, "slow"));
  }

  @Test
  void siblingPublishRaceHasExactlyOneWinnerAndTheLoserIsDiscarded() throws Exception {
    bootstrap();
    int n = 6;
    List<Lease> leases = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Lease lease = begin("root", "s" + i, stageManifest());
      for (TransactionRecord t : requests()) {
        svc.apply(lease, t, false);
      }
      leases.add(lease);
    }
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Receipt>> futures = new ArrayList<>();
    for (Lease lease : leases) {
      futures.add(
          pool.submit(
              () -> {
                go.await();
                return svc.publish(lease, "http");
              }));
    }
    go.countDown();
    List<String> winners = new ArrayList<>();
    int casLosers = 0;
    for (Future<Receipt> f : futures) {
      try {
        winners.add(f.get().generation());
      } catch (java.util.concurrent.ExecutionException e) {
        assertTrue(e.getCause() instanceof CasException, String.valueOf(e.getCause()));
        casLosers++;
      }
    }
    pool.shutdown();
    assertEquals(1, winners.size());
    assertEquals(n - 1, casLosers);
    assertEquals(Optional.of(winners.get(0)), store.current(NS));
    for (Lease lease : leases) {
      GenerationStatus status = store.info(NS, lease.generation()).orElseThrow().status();
      assertEquals(
          lease.generation().equals(winners.get(0))
              ? GenerationStatus.PUBLISHED
              : GenerationStatus.DISCARDED,
          status);
    }
    Integer published =
        db.sql("SELECT count(*) FROM generation WHERE namespace = ? AND status = 'PUBLISHED'")
            .param(NS)
            .query(Integer.class)
            .single();
    assertEquals(2, published); // root + the single winner
  }

  @Test
  void applyAfterPublicationIsFencedAndLeavesThePublishedGenerationUntouched() {
    bootstrap();
    Lease lease = begin("root", "g1", stageManifest());
    for (TransactionRecord t : requests()) {
      svc.apply(lease, t, false);
    }
    Receipt receipt = svc.publish(lease, "http");
    byte[] resout = store.resout(NS, "g1");
    assertThrows(
        FencedException.class,
        () -> svc.apply(lease, txn("00000002", 2, VALUATION, 'P', 1), false));
    assertThrows(FencedException.class, () -> svc.discard(lease));
    assertEquals(3, store.info(NS, "g1").orElseThrow().lastOrdinal());
    assertArrayEquals(resout, store.resout(NS, "g1"));
    assertEquals(receipt.resoutSha256(), Sha256.of(store.resout(NS, "g1")));
    assertEquals(GenerationStatus.PUBLISHED, store.info(NS, "g1").orElseThrow().status());
  }

  @Test
  void bootstrapEnforcesTheLegacyMasterTableCapAndOrder() {
    byte[][] rows = new byte[513][];
    for (int i = 0; i < rows.length; i++) {
      rows[i] = fresh(String.format("%08d", i + 1), 500_000, 20_000, 0).bytes();
    }
    byte[] tooMany = concat(rows);
    assertThrows(
        insurance.contract.v001.PolicyTable.BadMasterException.class,
        () ->
            svc.bootstrap(
                NS, "root", tooMany, manifest("bootstrap", 513, 0, tooMany, new byte[0])));
    byte[] cap = concat(java.util.Arrays.copyOf(rows, 512));
    assertEquals(
        512,
        svc.bootstrap(NS, "root", cap, manifest("bootstrap", 512, 0, cap, new byte[0]))
            .policiesCount());
    byte[] unordered = concat(rows[1], rows[0]);
    assertThrows(
        insurance.contract.v001.PolicyTable.BadMasterException.class,
        () ->
            svc.bootstrap(
                "u", "root", unordered, manifest("bootstrap", 2, 0, unordered, new byte[0])));
    byte[] duplicate = concat(rows[0], rows[0]);
    assertThrows(
        insurance.contract.v001.PolicyTable.BadMasterException.class,
        () ->
            svc.bootstrap(
                "d", "root", duplicate, manifest("bootstrap", 2, 0, duplicate, new byte[0])));
    assertTrue(store.info("u", "root").isEmpty());
    assertTrue(store.info("d", "root").isEmpty());
  }

  @Test
  void concurrentWritersUnderOneFenceGetContiguousOrdinalsAndSerialEvaluation() throws Exception {
    bootstrap();
    int n = 200;
    // every request is the same bytes, so the expected TXNIN is known before any of them arrives
    // regardless of the (nondeterministic) arrival order; a serial evaluation must yield exactly
    // one OKAY (the first arrival) and n-1 byte-exact replays (DUPL)
    TransactionRecord same = txn("00000001", 1, VALUATION, 'P', 100);
    byte[] expectedTxnin = new byte[n * 40];
    for (int i = 0; i < n; i++) {
      System.arraycopy(same.bytes(), 0, expectedTxnin, i * 40, 40);
    }
    Lease lease = begin("root", "g1", manifest("c", 2, n, seed(), expectedTxnin));
    ExecutorService pool = Executors.newFixedThreadPool(16);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Applied>> futures = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      futures.add(
          pool.submit(
              () -> {
                go.await();
                return svc.apply(lease, same, false);
              }));
    }
    go.countDown();
    List<Applied> applied = new ArrayList<>();
    for (Future<Applied> f : futures) {
      applied.add(f.get());
    }
    pool.shutdown();
    assertEquals(
        IntStream.rangeClosed(1, n).boxed().toList(),
        applied.stream().map(Applied::ordinal).sorted().map(Long::intValue).toList());
    long okay = applied.stream().filter(a -> a.evaluation().status() == Status.OKAY).count();
    long dupl = applied.stream().filter(a -> a.evaluation().status() == Status.DUPL).count();
    assertEquals(1, okay);
    assertEquals(n - 1, dupl);
    assertEquals(
        1L,
        applied.stream()
            .filter(a -> a.evaluation().status() == Status.OKAY)
            .findFirst()
            .orElseThrow()
            .ordinal());
    assertEquals(
        1,
        store
            .policy(NS, "g1", insurance.legacy.codec.Cp037.encode("00000001"))
            .orElseThrow()
            .seq());
    byte[] arrived =
        concat(
            db.sql("SELECT request FROM generation_entry WHERE generation_id = ? ORDER BY ordinal")
                .param(generationId("g1"))
                .query(byte[].class)
                .list()
                .toArray(byte[][]::new));
    assertArrayEquals(expectedTxnin, arrived);
    Receipt r = svc.publish(lease, "http");
    assertEquals(n, r.resultsCount());
    assertArrayEquals(expectedTxnin, store.requests(NS, "g1"));
  }

  // ---------------------------------------------------------------- retry boundaries

  @Test
  void retryAfterCommittedRequestIsDuplWhileStillLatestAndOrdrAfterIntervening() {
    bootstrap();
    Lease lease = begin("root", "g1", stageManifest());
    TransactionRecord p1 = txn("00000001", 1, VALUATION, 'P', 10_000);
    assertEquals(Status.OKAY, svc.apply(lease, p1, false).evaluation().status());
    // response lost after commit: identical retry is a byte-exact replay of the latest request
    assertEquals(Status.DUPL, svc.apply(lease, p1, false).evaluation().status());
    // an intervening accepted request makes the retried sequence lower than SSEQ
    assertEquals(
        Status.OKAY,
        svc.apply(lease, txn("00000001", 2, VALUATION, 'P', 10_000), false).evaluation().status());
    assertEquals(Status.ORDR, svc.apply(lease, p1, false).evaluation().status());
    // same sequence, different bytes: CNFL, not DUPL
    assertEquals(
        Status.CNFL,
        svc.apply(lease, txn("00000001", 2, VALUATION, 'P', 10_001), false).evaluation().status());
  }

  @Test
  void failureBeforeCommitLeavesNoOrdinalSoRetryIsOkay() {
    bootstrap();
    Lease lease = begin("root", "g1", stageManifest());
    TransactionRecord p1 = txn("00000001", 1, VALUATION, 'P', 10_000);
    RuntimeException boom = new RuntimeException("crash before commit");
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                store.commit(
                    lease,
                    p1,
                    m -> {
                      throw boom;
                    },
                    false));
    assertEquals(boom, thrown);
    assertEquals(0, store.info(NS, "g1").orElseThrow().lastOrdinal());
    assertEquals(0, store.peekResout(NS, "g1").length);
    Applied retry = svc.apply(lease, p1, false);
    assertEquals(Status.OKAY, retry.evaluation().status());
    assertEquals(1, retry.ordinal());
  }

  // ---------------------------------------------------------------- database guards

  @Test
  void publishedRowsRejectInsertUpdateAndDelete() {
    bootstrap();
    runGeneration("root", "g1");
    long id = generationId("g1");
    byte[] entry = requests().get(0).bytes();
    byte[] result = store.resout(NS, "g1");
    byte[] first = java.util.Arrays.copyOf(result, 96);
    assertThrows(
        DataAccessException.class,
        () ->
            db.sql(
                    "INSERT INTO generation_entry (generation_id, ordinal, request, result, typed)"
                        + " VALUES (?, 99, ?, ?, false)")
                .params(id, entry, first)
                .update());
    assertThrows(
        DataAccessException.class,
        () ->
            db.sql("UPDATE generation_entry SET typed = true WHERE generation_id = ?")
                .param(id)
                .update());
    assertThrows(
        DataAccessException.class,
        () -> db.sql("DELETE FROM generation_entry WHERE generation_id = ?").param(id).update());
    assertThrows(
        DataAccessException.class,
        () ->
            db.sql("UPDATE policy_state SET bytes = ? WHERE generation_id = ?")
                .params(a001().bytes(), id)
                .update());
    assertThrows(
        DataAccessException.class,
        () -> db.sql("DELETE FROM policy_state WHERE generation_id = ?").param(id).update());
    assertThrows(
        DataAccessException.class,
        () ->
            db.sql("UPDATE generation_output SET results = ? WHERE generation_id = ?")
                .params(new byte[0], id)
                .update());
    assertThrows(
        DataAccessException.class,
        () -> db.sql("DELETE FROM generation_output WHERE generation_id = ?").param(id).update());
    assertThrows(
        DataAccessException.class,
        () -> db.sql("UPDATE generation SET status = 'PENDING' WHERE id = ?").param(id).update());
    assertThrows(
        DataAccessException.class,
        () -> db.sql("UPDATE generation SET seed = ? WHERE id = ?").params(seed(), id).update());
    assertThrows(
        DataAccessException.class,
        () -> db.sql("DELETE FROM generation WHERE id = ?").param(id).update());
    assertArrayEquals(result, store.resout(NS, "g1"));
  }

  @Test
  void pendingEntriesAreAppendOnly() {
    bootstrap();
    Lease lease = begin("root", "g1", stageManifest());
    svc.apply(lease, requests().get(0), false);
    long id = generationId("g1");
    assertThrows(
        DataAccessException.class,
        () ->
            db.sql("UPDATE generation_entry SET typed = true WHERE generation_id = ?")
                .param(id)
                .update());
    assertThrows(
        DataAccessException.class,
        () -> db.sql("DELETE FROM generation_entry WHERE generation_id = ?").param(id).update());
  }

  // ---------------------------------------------------------------- restart

  @Test
  void pendingResultsAreNotServedAsPublishedAndAnotherInstanceLeavesLiveWritersAlone() {
    bootstrap();
    Lease lease = begin("root", "g1", stageManifest());
    svc.apply(lease, requests().get(0), false);
    assertThrows(GenerationException.class, () -> store.resout(NS, "g1"));
    assertEquals(96, store.peekResout(NS, "g1").length);
    assertTrue(store.receipt(NS, "g1").isEmpty());

    // a second instance starting while this writer is alive must not invalidate its generation
    try (JdbcGenerationStore other = open()) {
      assertEquals(List.of(), other.discardedOnOpen());
      assertEquals(List.of(NS + "/g1"), other.liveOnOpen());
      assertEquals(Optional.of("root"), other.current(NS));
      assertEquals(GenerationStatus.PENDING, other.info(NS, "g1").orElseThrow().status());
      // and the live writer keeps working
      assertEquals(2, svc.apply(lease, requests().get(1), false).ordinal());
      // the other instance does not own the lease: its commits under the same fence are refused
      assertThrows(
          GenerationException.class, () -> service(other).apply(lease, requests().get(2), false));
    }
  }

  @Test
  void orphanedPendingGenerationIsDiscardedOnceItsLeaseExpiredAndNeverResumed() {
    bootstrap();
    Lease lease = begin("root", "g1", stageManifest());
    svc.apply(lease, requests().get(0), false);
    // simulate the writer dying: stop its heartbeat and let the lease lapse
    store.close();
    db.sql("UPDATE generation SET lease_expires_at = now() - interval '1 second' WHERE name = 'g1'")
        .update();
    store = open();
    assertEquals(List.of(NS + "/g1"), store.discardedOnOpen());
    assertEquals(GenerationStatus.DISCARDED, store.info(NS, "g1").orElseThrow().status());
    assertEquals(
        "orphaned: writer lease expired at open",
        db.sql("SELECT discard_reason FROM generation WHERE name = 'g1'")
            .query(String.class)
            .single());
    assertEquals(Optional.of("root"), store.current(NS));
    assertThrows(GenerationException.class, () -> store.peekResout(NS, "g1"));
    assertThrows(
        FencedException.class, () -> service(store).apply(lease, requests().get(0), false));
    // the committed prefix is not resumed: a rerun starts again from the pinned parent
    Lease rerun = begin("root", "g1-rerun", stageManifest());
    assertEquals(1, svc.apply(rerun, requests().get(0), false).ordinal());
  }

  @Test
  void heartbeatRenewsOnlyThisWritersLeases() {
    bootstrap();
    begin("root", "g1", stageManifest());
    java.time.OffsetDateTime before =
        db.sql("SELECT lease_expires_at FROM generation WHERE name = 'g1'")
            .query(java.time.OffsetDateTime.class)
            .single();
    assertEquals(1, store.renewLeases());
    java.time.OffsetDateTime after =
        db.sql("SELECT lease_expires_at FROM generation WHERE name = 'g1'")
            .query(java.time.OffsetDateTime.class)
            .single();
    assertFalse(after.isBefore(before));
    try (JdbcGenerationStore other = open()) {
      assertEquals(0, other.renewLeases());
    }
  }

  @Test
  void restartFailsClosedWhenPublishedBytesDoNotMatchReceipt() {
    bootstrap();
    runGeneration("root", "g1");
    long id = generationId("g1");
    // the trigger forbids this through SQL; simulate storage corruption by disabling it
    db.sql("ALTER TABLE generation_output DISABLE TRIGGER ALL").update();
    byte[] results = store.resout(NS, "g1");
    results[16] ^= 0x01;
    db.sql("UPDATE generation_output SET results = ? WHERE generation_id = ?")
        .params(results, id)
        .update();
    db.sql("ALTER TABLE generation_output ENABLE TRIGGER ALL").update();
    assertThrows(JdbcGenerationStore.IntegrityException.class, this::open);
  }

  @Test
  void restartFailsClosedWhenOrdinalsAreNotContiguous() {
    bootstrap();
    runGeneration("root", "g1");
    long id = generationId("g1");
    db.sql("ALTER TABLE generation_entry DISABLE TRIGGER ALL").update();
    db.sql("DELETE FROM generation_entry WHERE generation_id = ? AND ordinal = 2")
        .param(id)
        .update();
    db.sql("ALTER TABLE generation_entry ENABLE TRIGGER ALL").update();
    assertThrows(JdbcGenerationStore.IntegrityException.class, this::open);
  }

  @Test
  void restartRebuildsAncestryAndServesOnlyReachableGenerations() {
    bootstrap();
    runGeneration("root", "g1");
    try (JdbcGenerationStore reopened = open()) {
      assertEquals(Optional.of("g1"), reopened.current(NS));
      assertArrayEquals(seed(), reopened.polout(NS, "root"));
      assertEquals(GenerationStatus.PUBLISHED, reopened.info(NS, "g1").orElseThrow().status());
    }
  }
}
