package insurance.ledger.generation;

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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileGenerationStoreTest {
  private static final String NS = "t";
  private static final ReceiptContext CTX =
      new ReceiptContext("batch", "0000000", "jar:sha256:test", "rates:test");

  @TempDir Path dir;

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
    FileGenerationStore store = FileGenerationStore.open(dir);
    service(store).bootstrap(NS, "root", seed(), manifest("bootstrap", 2, 0, seed(), new byte[0]));
    return store;
  }

  private Receipt runGeneration(PolicyLedgerService svc, String parent, String gen) {
    Lease lease = svc.begin(NS, parent, gen, Optional.of(seed()));
    for (TransactionRecord t : requests()) {
      svc.apply(lease, t, false);
    }
    return svc.publish(lease, manifest("a", 2, 3, seed(), txnin()), "batch");
  }

  @Test
  void bootstrapPublishesRootAndServesItsBytes() {
    FileGenerationStore store = bootstrapped();
    assertEquals(Optional.of("root"), store.current(NS));
    assertArrayEquals(seed(), store.polout(NS, "root"));
    assertEquals(0, store.resout(NS, "root").length);
    assertThrows(CasException.class, () -> store.bootstrap(NS, "root2", List.of(a001())));
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
    assertThrows(GenerationException.class, () -> svc.begin(NS, "g1", "g2", Optional.of(seed())));
    Lease g2 = svc.begin(NS, "g1", "g2", Optional.of(g1));
    Applied applied = svc.apply(g2, txn("00000001", 1, LedgerFixtures.VALUATION, 'P', 1), false);
    assertEquals(Status.CNFL, applied.evaluation().status());
    assertEquals(1, applied.ordinal());
  }

  @Test
  void publishRequiresPinnedManifestToMatchAppliedRequestsAndOutputs() {
    PolicyLedgerService svc = service(bootstrapped());
    Lease lease = svc.begin(NS, "root", "g1", Optional.empty());
    for (TransactionRecord t : requests()) {
      svc.apply(lease, t, false);
    }
    ExpectedManifest wrongCount = manifest("a", 2, 2, seed(), txnin());
    assertThrows(
        ExpectedManifest.ManifestException.class, () -> svc.publish(lease, wrongCount, "batch"));
    // failed publication discards the pending generation; the lease is dead
    assertThrows(GenerationException.class, () -> svc.apply(lease, requests().get(0), false));
    assertEquals(Optional.of("root"), svc.store().current(NS));
    assertTrue(Files.isDirectory(dir.resolve(NS).resolve("discarded").resolve("g1")));
  }

  @Test
  void onlyOneOpenWriterPerGenerationAndStaleFencesAreRejected() {
    PolicyLedgerService svc = service(bootstrapped());
    Lease first = svc.begin(NS, "root", "g1", Optional.empty());
    assertThrows(GenerationException.class, () -> svc.begin(NS, "root", "g1", Optional.empty()));
    svc.discard(first);
    assertThrows(GenerationException.class, () -> svc.apply(first, requests().get(0), false));
    Lease again = svc.begin(NS, "root", "g1b", Optional.empty());
    Lease forged = new Lease(NS, "g1b", "root", again.fence() + 1);
    assertThrows(FencedException.class, () -> svc.apply(forged, requests().get(0), false));
  }

  @Test
  void publishCasFailsWhenCurrentMovedUnderTheLease() {
    PolicyLedgerService svc = service(bootstrapped());
    Lease slow = svc.begin(NS, "root", "slow", Optional.empty());
    runGeneration(svc, "root", "fast");
    assertEquals(Optional.of("fast"), svc.store().current(NS));
    for (TransactionRecord t : requests()) {
      svc.apply(slow, t, false);
    }
    assertThrows(
        CasException.class, () -> svc.publish(slow, manifest("a", 2, 3, seed(), txnin()), "batch"));
    assertEquals(Optional.of("fast"), svc.store().current(NS));
    assertThrows(GenerationException.class, () -> svc.store().polout(NS, "slow"));
  }

  @Test
  void pendingResultsAreNotServedAsPublishedAndRestartDiscardsThem() {
    PolicyLedgerService svc = service(bootstrapped());
    Lease lease = svc.begin(NS, "root", "g1", Optional.empty());
    svc.apply(lease, requests().get(0), false);
    assertThrows(GenerationException.class, () -> svc.store().resout(NS, "g1"));
    assertEquals(96, svc.store().peekResout(NS, "g1").length);

    FileGenerationStore reopened = FileGenerationStore.open(dir);
    assertEquals(List.of(NS + "/g1"), reopened.discardedOnOpen());
    assertEquals(Optional.of("root"), reopened.current(NS));
    assertThrows(GenerationException.class, () -> reopened.peekResout(NS, "g1"));
  }

  @Test
  void orphanPublishedDirectoryIsNotReachable() throws IOException {
    FileGenerationStore store = bootstrapped();
    Path orphan = dir.resolve(NS).resolve("published").resolve("orphan");
    Files.createDirectories(orphan);
    Files.write(orphan.resolve("state.bin"), seed());
    assertThrows(GenerationException.class, () -> store.polout(NS, "orphan"));
    assertThrows(GenerationException.class, () -> store.begin(NS, "orphan", "g1"));
  }

  @Test
  void restartFailsClosedWhenPublishedBytesDoNotMatchReceipt() throws IOException {
    runGeneration(service(bootstrapped()), "root", "g1");
    Path results = dir.resolve(NS).resolve("published").resolve("g1").resolve("results.bin");
    byte[] raw = Files.readAllBytes(results);
    raw[16] ^= 0x01;
    Files.write(results, raw);
    assertThrows(FileGenerationStore.IntegrityException.class, () -> FileGenerationStore.open(dir));
  }

  @Test
  void restartRebuildsAncestryAndRefusesMissingParent() throws IOException {
    runGeneration(service(bootstrapped()), "root", "g1");
    FileGenerationStore reopened = FileGenerationStore.open(dir);
    assertEquals(Optional.of("g1"), reopened.current(NS));
    assertArrayEquals(seed(), reopened.polout(NS, "root"));
    Path rootDir = dir.resolve(NS).resolve("published").resolve("root");
    Files.move(rootDir, dir.resolve("hidden-root"));
    assertThrows(FileGenerationStore.IntegrityException.class, () -> FileGenerationStore.open(dir));
  }
}
