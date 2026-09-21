package insurance.ledger.generation;

import static insurance.ledger.LedgerFixtures.RATES_SHA256;
import static insurance.ledger.LedgerFixtures.a001;
import static insurance.ledger.LedgerFixtures.concat;
import static insurance.ledger.LedgerFixtures.fresh;
import static insurance.ledger.LedgerFixtures.manifest;
import static insurance.ledger.LedgerFixtures.txn;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import insurance.contract.v001.ContractV001;
import insurance.contract.v001.Status;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.LedgerFixtures;
import insurance.ledger.PolicyLedgerService;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.ledger.generation.GenerationStore.Applied;
import insurance.ledger.generation.GenerationStore.CasException;
import insurance.ledger.generation.GenerationStore.FencedException;
import insurance.ledger.generation.GenerationStore.GenerationException;
import insurance.ledger.generation.GenerationStore.Lease;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileGenerationStoreTest {
  private static final String NS = "t";
  private static final ReceiptContext CTX =
      new ReceiptContext("batch", "0000000", "jar:sha256:test", RATES_SHA256);

  @TempDir Path dir;
  @TempDir Path other;
  private final List<FileGenerationStore> opened = new ArrayList<>();

  @AfterEach
  void closeStores() {
    for (FileGenerationStore s : opened) {
      s.close();
    }
  }

  private FileGenerationStore open() {
    FileGenerationStore s = FileGenerationStore.open(dir);
    opened.add(s);
    return s;
  }

  private static byte[] seed() {
    return concat(a001().bytes(), fresh("00000002", 500_000, 20_000, 0).bytes());
  }

  private static List<TransactionRecord> requests() {
    return List.of(
        txn("00000001", 1, LedgerFixtures.VALUATION, 'P', 10_000),
        txn("00000001", 1, LedgerFixtures.VALUATION, 'P', 10_000),
        txn("00000002", 1, LedgerFixtures.VALUATION, 'W', 5_000));
  }

  private static byte[] txnin() {
    return Records.join(requests());
  }

  private PolicyLedgerService service(FileGenerationStore store) {
    return new PolicyLedgerService(ContractV001.frozen(), store, CTX);
  }

  private FileGenerationStore bootstrapped() {
    FileGenerationStore store = open();
    service(store).bootstrap(NS, "root", seed(), manifest("bootstrap", 2, 0, seed(), new byte[0]));
    return store;
  }

  private static ExpectedManifest stageManifest() {
    return manifest("a", 2, 3, seed(), txnin());
  }

  private static Lease begin(
      PolicyLedgerService svc, String parent, String gen, ExpectedManifest manifest) {
    return svc.begin(NS, parent, gen, Optional.of(seed()), manifest);
  }

  private Receipt runGeneration(PolicyLedgerService svc, String parent, String gen) {
    Lease lease = begin(svc, parent, gen, stageManifest());
    for (TransactionRecord t : requests()) {
      svc.apply(lease, t, false);
    }
    return svc.publish(lease, "batch");
  }

  @Test
  void bootstrapPublishesRootAndServesItsBytes() {
    FileGenerationStore store = bootstrapped();
    assertEquals(Optional.of("root"), store.current(NS));
    assertArrayEquals(seed(), store.polout(NS, "root"));
    assertEquals(0, store.resout(NS, "root").length);
    assertThrows(
        CasException.class,
        () ->
            store.bootstrap(
                NS,
                "root2",
                List.of(a001()),
                manifest("bootstrap", 1, 0, a001().bytes(), new byte[0])));
    assertEquals(
        Optional.of(manifest("bootstrap", 2, 0, seed(), new byte[0])),
        store.manifest(NS, "root").map(m -> m.withSourceSha256(null)));
  }

  @Test
  void applyPublishAndChain() {
    FileGenerationStore store = bootstrapped();
    PolicyLedgerService svc = service(store);
    Receipt receipt = runGeneration(svc, "root", "g1");
    assertEquals("root", receipt.parentGeneration());
    assertEquals(3, receipt.resultsCount());
    assertEquals(0, receipt.typedRequests());
    assertEquals(3, receipt.rawRequests());
    assertEquals(Sha256.of(txnin()), receipt.txninSha256());
    assertEquals(Sha256.of(store.resout(NS, "g1")), receipt.resoutSha256());
    assertEquals(Optional.of("g1"), store.current(NS));
    List<ResultRecord> results = Records.results(store.resout(NS, "g1"));
    assertEquals(
        List.of("OKAY", "DUPL", "OKAY"), results.stream().map(ResultRecord::status).toList());
    byte[] g1 = store.polout(NS, "g1");
    assertFalse(java.util.Arrays.equals(seed(), g1));

    // successor must be seeded from the pinned parent whose bytes match supplied POLIN
    assertThrows(
        GenerationException.class,
        () -> svc.begin(NS, "g1", "g2", Optional.of(seed()), manifest("b", 2, 1, g1, txnin())));
    Lease g2 = svc.begin(NS, "g1", "g2", Optional.of(g1), manifest("b", 2, 1, g1, txnin()));
    Applied applied = svc.apply(g2, txn("00000001", 1, LedgerFixtures.VALUATION, 'P', 1), false);
    assertEquals(Status.CNFL, applied.evaluation().status());
    assertEquals(1, applied.ordinal());
  }

  @Test
  void publishRequiresPinnedManifestToMatchAppliedRequestsAndOutputs() {
    PolicyLedgerService svc = service(bootstrapped());
    // pinned before any request: two requests expected, three arrive
    Lease lease = begin(svc, "root", "g1", manifest("a", 2, 2, seed(), txnin()));
    for (TransactionRecord t : requests()) {
      svc.apply(lease, t, false);
    }
    assertThrows(ExpectedManifest.ManifestException.class, () -> svc.publish(lease, "batch"));
    // failed publication discards the pending generation; the lease is dead
    assertThrows(GenerationException.class, () -> svc.apply(lease, requests().get(0), false));
    assertEquals(Optional.of("root"), svc.store().current(NS));
    assertTrue(Files.isDirectory(dir.resolve(NS).resolve("discarded").resolve("g1")));
  }

  @Test
  void onlyOneOpenWriterPerGenerationAndStaleFencesAreRejected() {
    PolicyLedgerService svc = service(bootstrapped());
    Lease first = begin(svc, "root", "g1", stageManifest());
    assertThrows(GenerationException.class, () -> begin(svc, "root", "g1", stageManifest()));
    svc.discard(first);
    assertThrows(GenerationException.class, () -> svc.apply(first, requests().get(0), false));
    Lease again = begin(svc, "root", "g1b", stageManifest());
    Lease forged = new Lease(NS, "g1b", "root", again.fence() + 1);
    assertThrows(FencedException.class, () -> svc.apply(forged, requests().get(0), false));
  }

  @Test
  void publishCasFailsWhenCurrentMovedUnderTheLease() {
    PolicyLedgerService svc = service(bootstrapped());
    Lease slow = begin(svc, "root", "slow", stageManifest());
    runGeneration(svc, "root", "fast");
    assertEquals(Optional.of("fast"), svc.store().current(NS));
    for (TransactionRecord t : requests()) {
      svc.apply(slow, t, false);
    }
    assertThrows(CasException.class, () -> svc.publish(slow, "batch"));
    assertEquals(Optional.of("fast"), svc.store().current(NS));
    assertThrows(GenerationException.class, () -> svc.store().polout(NS, "slow"));
  }

  @Test
  void pendingResultsAreNotServedAsPublishedAndRestartLeavesThemClaimable() {
    FileGenerationStore store = bootstrapped();
    PolicyLedgerService svc = service(store);
    Lease lease = begin(svc, "root", "g1", stageManifest());
    svc.apply(lease, requests().get(0), false);
    assertThrows(GenerationException.class, () -> svc.store().resout(NS, "g1"));
    assertEquals(96, svc.store().peekResout(NS, "g1").length);

    // the writer dies (its OS lock is released with it) and a fresh process opens the store
    store.close();
    FileGenerationStore reopened = open();
    assertEquals(List.of(NS + "/g1"), reopened.abandonedOnOpen());
    assertEquals(Optional.of("root"), reopened.current(NS));
    assertThrows(GenerationException.class, () -> reopened.peekResout(NS, "g1"));
    assertTrue(Files.isDirectory(dir.resolve(NS).resolve("pending").resolve("g1")));
    assertFalse(Files.isDirectory(dir.resolve(NS).resolve("discarded").resolve("g1")));
    // the dead writer's lease is not usable through the new instance
    PolicyLedgerService again = service(reopened);
    assertThrows(FencedException.class, () -> again.apply(lease, requests().get(0), false));

    // an explicit claim verifies the durable prefix and continues at ordinal 2
    GenerationStore.Claimed claimed = again.claim(NS, "g1", stageManifest());
    assertEquals(1, claimed.lastOrdinal());
    assertEquals(1, claimed.claims());
    assertTrue(claimed.lease().fence() > lease.fence());
    assertEquals(96, reopened.peekResout(NS, "g1").length);
    int next = PolicyLedgerService.resumeIndex(reopened.peekRequests(NS, "g1"), requests());
    assertEquals(1, next);
    assertThrows(FencedException.class, () -> again.apply(lease, requests().get(1), false));
    for (int i = next; i < requests().size(); i++) {
      assertEquals(i + 1, again.apply(claimed.lease(), requests().get(i), false).ordinal());
    }
    Receipt receipt = again.publish(claimed.lease(), "batch");
    assertEquals(Optional.of("g1"), reopened.current(NS));

    // the same input applied without interruption produces identical bytes
    FileGenerationStore straight = FileGenerationStore.open(other);
    opened.add(straight);
    PolicyLedgerService ref = service(straight);
    ref.bootstrap(NS, "root", seed(), manifest("bootstrap", 2, 0, seed(), new byte[0]));
    Receipt uninterrupted = runGeneration(ref, "root", "g1");
    assertArrayEquals(straight.resout(NS, "g1"), reopened.resout(NS, "g1"));
    assertArrayEquals(straight.polout(NS, "g1"), reopened.polout(NS, "g1"));
    assertEquals(uninterrupted.resoutSha256(), receipt.resoutSha256());
    assertEquals(uninterrupted.poloutSha256(), receipt.poloutSha256());
    assertEquals(1, reopened.claims(NS, "g1").size());
  }

  @Test
  void claimRefusesALiveWriterAndAnUnknownOrPublishedGeneration() {
    FileGenerationStore store = bootstrapped();
    PolicyLedgerService svc = service(store);
    Lease live = begin(svc, "root", "g1", stageManifest());
    assertThrows(FencedException.class, () -> svc.claim(NS, "g1", stageManifest()));
    assertThrows(GenerationException.class, () -> svc.claim(NS, "nope", stageManifest()));
    assertThrows(GenerationException.class, () -> svc.claim(NS, "root", stageManifest()));
    svc.apply(live, requests().get(0), false);
    assertEquals(0, store.claims(NS, "g1").size());
  }

  @Test
  void abandonedGenerationCanBeDiscardedInsteadOfClaimed() {
    FileGenerationStore store = bootstrapped();
    Lease lease = begin(service(store), "root", "g1", stageManifest());
    service(store).apply(lease, requests().get(0), false);
    store.close();
    FileGenerationStore reopened = open();
    assertThrows(GenerationException.class, () -> reopened.discardAbandoned(NS, "nope", "test"));
    reopened.discardAbandoned(NS, "g1", "operator chose discard");
    assertTrue(Files.isDirectory(dir.resolve(NS).resolve("discarded").resolve("g1")));
    assertThrows(GenerationException.class, () -> reopened.claim(NS, "g1", stageManifest()));
    assertEquals(Optional.of("root"), reopened.current(NS));
  }

  @Test
  void claimFailsClosedOnCorruptJournalChangedManifestOrChangedContract() throws IOException {
    FileGenerationStore store = bootstrapped();
    Lease lease = begin(service(store), "root", "g1", stageManifest());
    service(store).apply(lease, requests().get(0), false);
    service(store).apply(lease, requests().get(1), false);
    store.close();
    Path journal = dir.resolve(NS).resolve("pending").resolve("g1").resolve("journal.bin");
    byte[] good = Files.readAllBytes(journal);

    // a different (but internally valid) manifest is not the pinned one
    FileGenerationStore reopened = open();
    ExpectedManifest unpinned = manifest("b", 2, 3, seed(), txnin());
    assertThrows(
        GenerationStore.CheckpointException.class, () -> reopened.claim(NS, "g1", unpinned));

    // a flipped result byte: the contract does not reproduce the stored result
    byte[] badResult = good.clone();
    badResult[TransactionRecord.LENGTH + 5] ^= 0x01;
    Files.write(journal, badResult);
    assertThrows(
        GenerationStore.CheckpointException.class, () -> reopened.claim(NS, "g1", stageManifest()));

    // a flipped successor byte
    byte[] badSuccessor = good.clone();
    badSuccessor[TransactionRecord.LENGTH + ResultRecord.LENGTH + 1 + 100] ^= 0x01;
    Files.write(journal, badSuccessor);
    assertThrows(
        GenerationStore.CheckpointException.class, () -> reopened.claim(NS, "g1", stageManifest()));

    // a torn tail
    Files.write(journal, java.util.Arrays.copyOf(good, good.length - 7));
    assertThrows(
        GenerationStore.CheckpointException.class, () -> reopened.claim(NS, "g1", stageManifest()));

    // a dropped first entry (ordinal gap relative to the summary and the chain)
    Files.write(journal, java.util.Arrays.copyOfRange(good, good.length / 2, good.length));
    assertThrows(
        GenerationStore.CheckpointException.class, () -> reopened.claim(NS, "g1", stageManifest()));

    // intact journal but a different running contract identity
    Files.write(journal, good);
    reopened.close();
    FileGenerationStore relabelled =
        FileGenerationStore.open(
            dir,
            insurance.ledger.ContractBinding.relabelled(
                ContractV001.frozen(), RATES_SHA256, "contract:sha256:other"));
    opened.add(relabelled);
    assertThrows(
        GenerationStore.CheckpointException.class,
        () -> relabelled.claim(NS, "g1", stageManifest()));
    relabelled.close();

    // nothing was published or discarded by any of the failed claims
    FileGenerationStore last = open();
    assertEquals(Optional.of("root"), last.current(NS));
    assertEquals(List.of(NS + "/g1"), last.abandonedOnOpen());
    assertEquals(2, last.claim(NS, "g1", stageManifest()).lastOrdinal());
  }

  @Test
  void secondOpenOfALiveStoreIsRefusedBeforeItCanTouchPendingWork() throws Exception {
    FileGenerationStore store = bootstrapped();
    PolicyLedgerService svc = service(store);
    Lease lease = begin(svc, "root", "g1", stageManifest());
    svc.apply(lease, requests().get(0), false);
    // same JVM, second instance: refused by the OS lock (JVM-wide overlapping lock)
    assertThrows(
        FileGenerationStore.StoreLockedException.class, () -> FileGenerationStore.open(dir));
    // another process: refused too, and the live pending directory is untouched
    Process other = TwoProcess.tryOpen(dir);
    assertEquals(5, other.waitFor());
    assertTrue(Files.isDirectory(dir.resolve(NS).resolve("pending").resolve("g1")));
    assertEquals(2, svc.apply(lease, requests().get(1), false).ordinal());
    // once this writer closes, the other process may open; the pending work is left claimable
    store.close();
    Process after = TwoProcess.tryOpen(dir);
    assertEquals(0, after.waitFor());
    assertTrue(Files.isDirectory(dir.resolve(NS).resolve("pending").resolve("g1")));
    assertFalse(Files.isDirectory(dir.resolve(NS).resolve("discarded").resolve("g1")));
    assertThrows(GenerationException.class, () -> svc.apply(lease, requests().get(2), false));
  }

  @Test
  void closedStoreRefusesWorkAndReleasesTheLock() {
    FileGenerationStore store = bootstrapped();
    store.close();
    assertThrows(
        GenerationException.class, () -> begin(service(store), "root", "g1", stageManifest()));
    FileGenerationStore again = open();
    assertEquals(Optional.of("root"), again.current(NS));
  }

  @Test
  void orphanPublishedDirectoryIsNotReachable() throws IOException {
    FileGenerationStore store = bootstrapped();
    Path orphan = dir.resolve(NS).resolve("published").resolve("orphan");
    Files.createDirectories(orphan);
    Files.write(orphan.resolve("state.bin"), seed());
    assertThrows(GenerationException.class, () -> store.polout(NS, "orphan"));
    assertThrows(GenerationException.class, () -> store.begin(NS, "orphan", "g1", stageManifest()));
  }

  @Test
  void restartFailsClosedWhenPublishedBytesDoNotMatchReceipt() throws IOException {
    FileGenerationStore store = bootstrapped();
    runGeneration(service(store), "root", "g1");
    store.close();
    Path results = dir.resolve(NS).resolve("published").resolve("g1").resolve("results.bin");
    byte[] raw = Files.readAllBytes(results);
    raw[16] ^= 0x01;
    Files.write(results, raw);
    assertThrows(FileGenerationStore.IntegrityException.class, () -> FileGenerationStore.open(dir));
  }

  @Test
  void restartRebuildsAncestryAndRefusesMissingParent() throws IOException {
    FileGenerationStore store = bootstrapped();
    runGeneration(service(store), "root", "g1");
    store.close();
    FileGenerationStore reopened = open();
    assertEquals(Optional.of("g1"), reopened.current(NS));
    assertArrayEquals(seed(), reopened.polout(NS, "root"));
    reopened.close();
    Path rootDir = dir.resolve(NS).resolve("published").resolve("root");
    Files.move(rootDir, dir.resolve("hidden-root"));
    assertThrows(FileGenerationStore.IntegrityException.class, () -> FileGenerationStore.open(dir));
  }
}
