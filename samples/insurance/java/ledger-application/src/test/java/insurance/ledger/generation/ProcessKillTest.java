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
import insurance.ledger.Json;
import insurance.ledger.LedgerFixtures;
import insurance.ledger.Receipt;
import insurance.ledger.batch.BatchRunner;
import insurance.ledger.generation.GenerationStore.GenerationException;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real process termination at defined lifecycle boundaries: the batch CLI runs in a child JVM and
 * halts itself ({@code Runtime.halt(137)}: no shutdown hooks, no close, no discard) before a
 * commit, after a commit whose response is therefore lost, before publication, between the two
 * publication renames and after publication. A second process (or this JVM) then restarts on the
 * same store and the durable state is inspected. The generation is rerun from the pinned published
 * parent and compared with the pure INSBAT emulation.
 *
 * <p>These are file-store facts about {@code rename(2)}-based durability on this host; they are not
 * a power-loss or PostgreSQL claim.
 */
class ProcessKillTest {
  private static final String NS = "t";
  private static final int ENTRY =
      TransactionRecord.LENGTH + ResultRecord.LENGTH + 1 + PolicyRecord.LENGTH;

  @TempDir Path dir;
  private Path store;
  private Path polin;
  private Path txnin;
  private Path rootManifest;
  private Path stageManifest;

  private static byte[] seed() {
    return concat(a001().bytes(), fresh("00000002", 500_000, 20_000, 0).bytes());
  }

  private static List<TransactionRecord> requests() {
    return List.of(
        txn("00000001", 1, LedgerFixtures.VALUATION, 'P', 10_000),
        txn("00000001", 1, LedgerFixtures.VALUATION, 'P', 10_000),
        txn("00000002", 1, LedgerFixtures.VALUATION, 'W', 5_000),
        txn("00000002", 2, LedgerFixtures.VALUATION, 'L', 1_000));
  }

  private static byte[] txninBytes() {
    return Records.join(requests());
  }

  @BeforeEach
  void files() throws Exception {
    store = dir.resolve("store");
    polin = Files.write(dir.resolve("polin.bin"), seed());
    txnin = Files.write(dir.resolve("txnin.bin"), txninBytes());
    rootManifest =
        Files.write(
            dir.resolve("root.json"), Json.bytes(manifest("bootstrap", 2, 0, seed(), new byte[0])));
    stageManifest =
        Files.write(
            dir.resolve("stage.json"),
            Json.bytes(manifest("a", 2, requests().size(), seed(), txninBytes())));
    TwoProcess.Result boot =
        TwoProcess.run(
            "bootstrap",
            "--store",
            store.toString(),
            "--namespace",
            NS,
            "--generation",
            "root",
            "--polin",
            polin.toString(),
            "--manifest",
            rootManifest.toString());
    assertEquals(0, boot.exit(), boot.output());
  }

  private String[] runArgs(String gen, String... extra) {
    List<String> a =
        new java.util.ArrayList<>(
            List.of(
                "run",
                "--store",
                store.toString(),
                "--namespace",
                NS,
                "--parent",
                "root",
                "--generation",
                gen,
                "--polin",
                polin.toString(),
                "--txnin",
                txnin.toString(),
                "--manifest",
                stageManifest.toString(),
                "--out",
                dir.resolve("out-" + gen).toString()));
    a.addAll(List.of(extra));
    return a.toArray(String[]::new);
  }

  private Path pending(String gen) {
    return store.resolve(NS).resolve("pending").resolve(gen);
  }

  private Path published(String gen) {
    return store.resolve(NS).resolve("published").resolve(gen);
  }

  private Path discarded(String gen) {
    return store.resolve(NS).resolve("discarded").resolve(gen);
  }

  private static BatchRunner.Output oracle() {
    return new BatchRunner(ContractV001.frozen()).run(seed(), txninBytes());
  }

  private long journalEntries(Path gen) throws IOException {
    long size = Files.size(gen.resolve("journal.bin"));
    assertEquals(0, size % ENTRY, "torn journal entry");
    return size / ENTRY;
  }

  /** Restart in a second process: fail-closed open, then report. */
  private TwoProcess.Result restart() throws Exception {
    TwoProcess.Result r = TwoProcess.run("verify", "--store", store.toString());
    assertEquals(0, r.exit(), r.output());
    return r;
  }

  private void rerunFromParentMatchesOracle(String gen) throws Exception {
    TwoProcess.Result rerun = TwoProcess.run(runArgs(gen));
    assertEquals(0, rerun.exit(), rerun.output());
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(Optional.of(gen), s.current(NS));
      assertArrayEquals(oracle().resout(), s.resout(NS, gen));
      assertArrayEquals(oracle().polout(), s.polout(NS, gen));
    }
  }

  @Test
  void killBeforeCommitLeavesNoTraceOfTheRequestAndRetryIsAFreshOkay() throws Exception {
    TwoProcess.Result killed = TwoProcess.run(runArgs("g1", "--kill-before-commit", "3"));
    assertEquals(TwoProcess.HALTED, killed.exit(), killed.output());
    assertTrue(killed.output().contains("kill switch kill-before-commit at ordinal 2"));
    // durable state: two committed entries, the third request never reached the journal
    assertTrue(Files.isDirectory(pending("g1")));
    assertEquals(2, journalEntries(pending("g1")));
    GenerationInfo info = Json.read(pending("g1").resolve("generation.json"), GenerationInfo.class);
    assertEquals(GenerationStatus.PENDING, info.status());
    assertEquals(2, info.lastOrdinal());

    TwoProcess.Result r = restart();
    assertTrue(r.output().contains("\"" + NS + "/g1\""), r.output());
    assertTrue(Files.isDirectory(discarded("g1")));
    assertFalse(Files.isDirectory(pending("g1")));
    assertFalse(Files.isDirectory(published("g1")));
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(Optional.of("root"), s.current(NS));
      assertEquals(GenerationStatus.DISCARDED, s.info(NS, "g1").orElseThrow().status());
      assertThrows(GenerationException.class, () -> s.resout(NS, "g1"));
    }
    // the retry is a rerun from the pinned parent: every request is first-seen, so OKAY again
    rerunFromParentMatchesOracle("g2");
    Receipt receipt = Json.read(dir.resolve("out-g2").resolve("receipt.json"), Receipt.class);
    assertEquals(requests().size(), receipt.resultsCount());
  }

  @Test
  void killAfterCommitLosesTheResponseButNotTheCommittedRequest() throws Exception {
    TwoProcess.Result killed = TwoProcess.run(runArgs("g1", "--kill-after-commit", "3"));
    assertEquals(TwoProcess.HALTED, killed.exit(), killed.output());
    assertTrue(killed.output().contains("kill switch kill-after-commit at ordinal 3"));
    assertFalse(Files.exists(dir.resolve("out-g1")), "no response/output was ever delivered");
    // the third request IS durable: three contiguous entries with the third result recorded
    assertEquals(3, journalEntries(pending("g1")));
    byte[] journal = Files.readAllBytes(pending("g1").resolve("journal.bin"));
    byte[] third = java.util.Arrays.copyOfRange(journal, 2 * ENTRY, 2 * ENTRY + 40);
    assertArrayEquals(requests().get(2).bytes(), third);
    byte[] thirdResult = java.util.Arrays.copyOfRange(journal, 2 * ENTRY + 40, 2 * ENTRY + 136);
    assertArrayEquals(java.util.Arrays.copyOfRange(oracle().resout(), 2 * 96, 3 * 96), thirdResult);

    // restart fails closed: the committed-but-unpublished work is discarded, never published
    restart();
    assertTrue(Files.isDirectory(discarded("g1")));
    assertEquals(3, journalEntries(discarded("g1")));
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(Optional.of("root"), s.current(NS));
      assertThrows(GenerationException.class, () -> s.resout(NS, "g1"));
    }
    rerunFromParentMatchesOracle("g2");
  }

  @Test
  void killBeforePublishKeepsAllCommitsPendingAndRestartDiscardsThem() throws Exception {
    TwoProcess.Result killed = TwoProcess.run(runArgs("g1", "--kill-before-publish", "1"));
    assertEquals(TwoProcess.HALTED, killed.exit(), killed.output());
    assertEquals(requests().size(), journalEntries(pending("g1")));
    assertFalse(Files.exists(pending("g1").resolve("receipt.json")));
    restart();
    assertTrue(Files.isDirectory(discarded("g1")));
    assertFalse(Files.isDirectory(published("g1")));
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(Optional.of("root"), s.current(NS));
      assertTrue(s.receipt(NS, "g1").isEmpty());
    }
    rerunFromParentMatchesOracle("g2");
  }

  @Test
  void killBetweenTheTwoPublicationRenamesLeavesAnOrphanThatIsNeverServed() throws Exception {
    TwoProcess.Result killed =
        TwoProcess.run(
            List.of("-D" + FileGenerationStore.HALT_BETWEEN_RENAMES + "=true"), runArgs("g1"));
    assertEquals(TwoProcess.HALTED, killed.exit(), killed.output());
    // first rename happened, second did not
    assertTrue(Files.isDirectory(published("g1")));
    assertTrue(Files.exists(published("g1").resolve("receipt.json")));
    assertFalse(Files.isDirectory(pending("g1")));
    assertEquals(
        "root",
        Json.read(store.resolve(NS).resolve("current.json"), CurrentPointer.class).current());

    restart();
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(Optional.of("root"), s.current(NS));
      // the orphan directory exists but is not reachable through the current ancestry
      assertThrows(GenerationException.class, () -> s.resout(NS, "g1"));
      assertThrows(GenerationException.class, () -> s.polout(NS, "g1"));
      assertTrue(s.receipt(NS, "g1").isEmpty());
    }
    // the orphan name is burnt; the rerun uses a new generation name from the same pinned parent
    rerunFromParentMatchesOracle("g2");
  }

  @Test
  void killAfterPublishLosesOnlyTheResponseAndRestartServesThePublication() throws Exception {
    TwoProcess.Result killed = TwoProcess.run(runArgs("g1", "--kill-after-publish", "1"));
    assertEquals(TwoProcess.HALTED, killed.exit(), killed.output());
    assertFalse(Files.exists(dir.resolve("out-g1")), "response lost after publication");
    restart();
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(Optional.of("g1"), s.current(NS));
      assertArrayEquals(oracle().resout(), s.resout(NS, "g1"));
      assertArrayEquals(oracle().polout(), s.polout(NS, "g1"));
      assertTrue(s.receipt(NS, "g1").isPresent());
      assertEquals(List.of(), s.discardedOnOpen());
    }
    // a retry of the whole run from the same parent is now a CAS failure, not a duplicate publish
    TwoProcess.Result retry = TwoProcess.run(runArgs("g2"));
    assertEquals(3, retry.exit(), retry.output());
    assertTrue(retry.output().contains("CasException"), retry.output());
  }

  record CurrentPointer(@com.fasterxml.jackson.annotation.JsonProperty("current") String current) {}
}
