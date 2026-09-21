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
import insurance.legacy.codec.Records;
import insurance.legacy.codec.TransactionRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real process termination at defined lifecycle boundaries: the batch CLI runs in a child JVM and
 * halts itself ({@code Runtime.halt(137)}: no shutdown hooks, no close, no discard) before a
 * commit, after a commit whose response is therefore lost, before publication, between the two
 * publication renames and after publication. A second process then restarts on the same store: the
 * dead writer's pending generation is reported as abandoned and left in place, a replacement
 * process claims it (the store re-evaluates every durable entry against the pinned manifest and the
 * running contract), continues at {@code lastOrdinal + 1} without re-sending any committed request,
 * and publishes. The published bytes are compared with an uninterrupted run and with the pure
 * INSBAT emulation. Corrupt journals fail the claim closed, and explicit discard remains the
 * separate way out.
 *
 * <p>These are file-store facts about {@code rename(2)}-based durability on this host; they are not
 * a power-loss or PostgreSQL claim.
 */
class ProcessKillTest {
  private static final String NS = "t";
  private static final int ENTRY = FileGenerationStore.ENTRY;

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
    return args("run", gen, "root", extra);
  }

  private String[] resumeArgs(String gen, String... extra) {
    return args("resume", gen, null, extra);
  }

  private String[] args(String command, String gen, String parent, String... extra) {
    List<String> a =
        new java.util.ArrayList<>(
            List.of(command, "--store", store.toString(), "--namespace", NS, "--generation", gen));
    if (parent != null) {
      a.addAll(List.of("--parent", parent));
    }
    a.addAll(
        List.of(
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

  /** Restart in a second process: fail-closed open, then report; nothing is moved or discarded. */
  private TwoProcess.Result restartReportsAbandoned(String gen) throws Exception {
    TwoProcess.Result r = TwoProcess.run("verify", "--store", store.toString());
    assertEquals(0, r.exit(), r.output());
    assertTrue(r.output().contains("\"abandoned_on_open\""), r.output());
    assertTrue(r.output().contains("\"" + NS + "/" + gen + "\""), r.output());
    assertTrue(Files.isDirectory(pending(gen)));
    assertFalse(Files.isDirectory(discarded(gen)));
    assertFalse(Files.isDirectory(published(gen)));
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(Optional.of("root"), s.current(NS));
      assertEquals(GenerationStatus.PENDING, s.info(NS, gen).orElseThrow().status());
      assertThrows(GenerationException.class, () -> s.resout(NS, gen));
      assertTrue(s.receipt(NS, gen).isEmpty());
    }
    return r;
  }

  /** A replacement process claims the abandoned generation and continues it to publication. */
  private Map<String, Object> resumeMatchesOracle(String gen, long verifiedLastOrdinal)
      throws Exception {
    TwoProcess.Result resumed = TwoProcess.run(resumeArgs(gen));
    assertEquals(0, resumed.exit(), resumed.output());
    assertTrue(
        resumed.output().contains("verified prefix " + verifiedLastOrdinal + " of 4"),
        resumed.output());
    Map<String, Object> resume =
        Json.MAPPER.readValue(
            Files.readAllBytes(dir.resolve("out-" + gen).resolve("resume.json")),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    assertEquals((int) verifiedLastOrdinal, resume.get("verified_last_ordinal"));
    assertEquals((int) verifiedLastOrdinal + 1, resume.get("resumed_at_ordinal"));
    assertEquals(4 - (int) verifiedLastOrdinal, resume.get("applied_after_claim"));
    assertEquals(requests().size(), journalEntries(published(gen)));
    assertFalse(Files.isDirectory(pending(gen)));
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(Optional.of(gen), s.current(NS));
      assertArrayEquals(oracle().resout(), s.resout(NS, gen));
      assertArrayEquals(oracle().polout(), s.polout(NS, gen));
      assertEquals(1, s.claims(NS, gen).size());
      assertEquals(verifiedLastOrdinal, s.claims(NS, gen).get(0).verifiedLastOrdinal());
      Receipt receipt = s.receipt(NS, gen).orElseThrow();
      assertEquals(requests().size(), receipt.resultsCount());
    }
    assertArrayEquals(
        oracle().resout(), Files.readAllBytes(dir.resolve("out-" + gen).resolve("resout.bin")));
    // the same input, run uninterrupted from the same parent in a fresh store, is byte-identical
    Path straight = dir.resolve("straight");
    TwoProcess.Result boot =
        TwoProcess.run(
            "bootstrap",
            "--store",
            straight.toString(),
            "--namespace",
            NS,
            "--generation",
            "root",
            "--polin",
            polin.toString(),
            "--manifest",
            rootManifest.toString());
    assertEquals(0, boot.exit(), boot.output());
    TwoProcess.Result uninterrupted =
        TwoProcess.run(
            "run",
            "--store",
            straight.toString(),
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
            dir.resolve("out-straight").toString());
    assertEquals(0, uninterrupted.exit(), uninterrupted.output());
    assertArrayEquals(
        Files.readAllBytes(dir.resolve("out-straight").resolve("resout.bin")),
        Files.readAllBytes(dir.resolve("out-" + gen).resolve("resout.bin")));
    assertArrayEquals(
        Files.readAllBytes(dir.resolve("out-straight").resolve("polout.bin")),
        Files.readAllBytes(dir.resolve("out-" + gen).resolve("polout.bin")));
    return resume;
  }

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
  void killBeforeCommitLeavesNoTraceOfTheRequestAndResumeContinuesAtOrdinalThree()
      throws Exception {
    TwoProcess.Result killed = TwoProcess.run(runArgs("g1", "--kill-before-commit", "3"));
    assertEquals(TwoProcess.HALTED, killed.exit(), killed.output());
    assertTrue(killed.output().contains("kill switch kill-before-commit at ordinal 2"));
    // durable state: two committed entries, the third request never reached the journal
    assertTrue(Files.isDirectory(pending("g1")));
    assertEquals(2, journalEntries(pending("g1")));
    GenerationInfo info = Json.read(pending("g1").resolve("generation.json"), GenerationInfo.class);
    assertEquals(GenerationStatus.PENDING, info.status());
    assertEquals(2, info.lastOrdinal());

    restartReportsAbandoned("g1");
    // the replacement writer's fence is above the dead writer's
    long deadFence = info.fence();
    resumeMatchesOracle("g1", 2);
    GenerationInfo after =
        Json.read(published("g1").resolve("generation.json"), GenerationInfo.class);
    assertTrue(after.fence() > deadFence);
    // a second claim of the now-published generation is refused
    TwoProcess.Result again = TwoProcess.run(resumeArgs("g1"));
    assertEquals(3, again.exit(), again.output());
    assertTrue(again.output().contains("PUBLISHED"), again.output());
  }

  @Test
  void killAfterCommitLosesTheResponseAndResumeDoesNotReapplyTheCommittedRequest()
      throws Exception {
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

    restartReportsAbandoned("g1");
    // only the fourth request is applied after the claim: exactly four entries, no DUPL for #3
    resumeMatchesOracle("g1", 3);
    byte[] finalJournal = Files.readAllBytes(published("g1").resolve("journal.bin"));
    assertEquals(4 * ENTRY, finalJournal.length);
    assertArrayEquals(journal, java.util.Arrays.copyOf(finalJournal, 3 * ENTRY));
  }

  @Test
  void killBeforePublishKeepsAllCommitsPendingAndResumeOnlyPublishes() throws Exception {
    TwoProcess.Result killed = TwoProcess.run(runArgs("g1", "--kill-before-publish", "1"));
    assertEquals(TwoProcess.HALTED, killed.exit(), killed.output());
    assertEquals(requests().size(), journalEntries(pending("g1")));
    assertFalse(Files.exists(pending("g1").resolve("receipt.json")));
    restartReportsAbandoned("g1");
    Map<String, Object> resume = resumeMatchesOracle("g1", 4);
    assertEquals(0, resume.get("applied_after_claim"));
  }

  @Test
  void resumeOfAnAbandonedGenerationCanBeKilledAndResumedAgain() throws Exception {
    TwoProcess.Result killed = TwoProcess.run(runArgs("g1", "--kill-after-commit", "1"));
    assertEquals(TwoProcess.HALTED, killed.exit(), killed.output());
    restartReportsAbandoned("g1");
    // the first replacement writer dies too, after committing ordinal 3
    TwoProcess.Result killedAgain = TwoProcess.run(resumeArgs("g1", "--kill-after-commit", "3"));
    assertEquals(TwoProcess.HALTED, killedAgain.exit(), killedAgain.output());
    assertTrue(killedAgain.output().contains("verified prefix 1 of 4"), killedAgain.output());
    assertEquals(3, journalEntries(pending("g1")));
    restartReportsAbandoned("g1");
    // the second replacement verifies three entries (one from each earlier writer) and finishes
    TwoProcess.Result resumed = TwoProcess.run(resumeArgs("g1"));
    assertEquals(0, resumed.exit(), resumed.output());
    assertTrue(resumed.output().contains("verified prefix 3 of 4"), resumed.output());
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(Optional.of("g1"), s.current(NS));
      assertArrayEquals(oracle().resout(), s.resout(NS, "g1"));
      assertArrayEquals(oracle().polout(), s.polout(NS, "g1"));
      List<FileGenerationStore.ClaimRecord> claims = s.claims(NS, "g1");
      assertEquals(2, claims.size());
      assertEquals(1, claims.get(0).verifiedLastOrdinal());
      assertEquals(3, claims.get(1).verifiedLastOrdinal());
      assertTrue(claims.get(1).newFence() > claims.get(0).newFence());
      assertEquals(claims.get(0).newFence(), claims.get(1).oldFence());
    }
  }

  @Test
  void corruptJournalFailsTheClaimClosedAndLeavesTheGenerationAbandoned() throws Exception {
    TwoProcess.Result killed = TwoProcess.run(runArgs("g1", "--kill-after-commit", "3"));
    assertEquals(TwoProcess.HALTED, killed.exit(), killed.output());
    restartReportsAbandoned("g1");
    Path journal = pending("g1").resolve("journal.bin");
    byte[] good = Files.readAllBytes(journal);

    // result byte of entry 2 flipped: the contract does not reproduce it
    byte[] badResult = good.clone();
    badResult[ENTRY + 40 + 10] ^= 0x01;
    Files.write(journal, badResult);
    TwoProcess.Result r1 = TwoProcess.run(resumeArgs("g1"));
    assertEquals(3, r1.exit(), r1.output());
    assertTrue(r1.output().contains("CheckpointException"), r1.output());
    assertTrue(r1.output().contains("result"), r1.output());

    // successor byte of entry 1 flipped
    byte[] badSuccessor = good.clone();
    badSuccessor[40 + 96 + 1 + 64] ^= 0x01;
    Files.write(journal, badSuccessor);
    TwoProcess.Result r2 = TwoProcess.run(resumeArgs("g1"));
    assertEquals(3, r2.exit(), r2.output());
    assertTrue(r2.output().contains("CheckpointException"), r2.output());

    // chain byte flipped: bytes agree with the contract but not with the recorded checkpoint
    byte[] badChain = good.clone();
    badChain[2 * ENTRY - 1] ^= 0x01;
    Files.write(journal, badChain);
    TwoProcess.Result r3 = TwoProcess.run(resumeArgs("g1"));
    assertEquals(3, r3.exit(), r3.output());
    assertTrue(r3.output().contains("CheckpointException"), r3.output());

    // torn tail
    Files.write(journal, java.util.Arrays.copyOf(good, good.length - 1));
    TwoProcess.Result r4 = TwoProcess.run(resumeArgs("g1"));
    assertEquals(3, r4.exit(), r4.output());
    assertTrue(r4.output().contains("torn"), r4.output());

    // missing middle entry (ordinal gap)
    byte[] gap = new byte[2 * ENTRY];
    System.arraycopy(good, 0, gap, 0, ENTRY);
    System.arraycopy(good, 2 * ENTRY, gap, ENTRY, ENTRY);
    Files.write(journal, gap);
    TwoProcess.Result r5 = TwoProcess.run(resumeArgs("g1"));
    assertEquals(3, r5.exit(), r5.output());
    assertTrue(r5.output().contains("CheckpointException"), r5.output());

    // a different TXNIN for the same manifest is rejected before any claim
    Files.write(journal, good);
    byte[] otherTxnin = txninBytes().clone();
    otherTxnin[3] ^= 0x01;
    Path other = Files.write(dir.resolve("other-txnin.bin"), otherTxnin);
    String[] changedInput = resumeArgs("g1");
    for (int i = 0; i < changedInput.length; i++) {
      if (changedInput[i].equals(txnin.toString())) {
        changedInput[i] = other.toString();
      }
    }
    TwoProcess.Result r6 = TwoProcess.run(changedInput);
    assertEquals(2, r6.exit(), r6.output());

    // every failure left the generation exactly as found; the intact journal then resumes
    restartReportsAbandoned("g1");
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(0, s.claims(NS, "g1").size());
    }
    resumeMatchesOracle("g1", 3);
  }

  @Test
  void abandonedGenerationCanBeExplicitlyDiscardedAndRerunFromTheParent() throws Exception {
    TwoProcess.Result killed = TwoProcess.run(runArgs("g1", "--kill-after-commit", "2"));
    assertEquals(TwoProcess.HALTED, killed.exit(), killed.output());
    restartReportsAbandoned("g1");
    TwoProcess.Result discard =
        TwoProcess.run(
            "discard-abandoned",
            "--store",
            store.toString(),
            "--namespace",
            NS,
            "--generation",
            "g1",
            "--reason",
            "operator chose rerun over resume");
    assertEquals(0, discard.exit(), discard.output());
    assertTrue(Files.isDirectory(discarded("g1")));
    assertEquals(2, journalEntries(discarded("g1")));
    assertFalse(Files.isDirectory(pending("g1")));
    // discarded generations are neither resumable nor published
    TwoProcess.Result resume = TwoProcess.run(resumeArgs("g1"));
    assertEquals(3, resume.exit(), resume.output());
    try (FileGenerationStore s = FileGenerationStore.open(store)) {
      assertEquals(Optional.of("root"), s.current(NS));
      assertEquals(GenerationStatus.DISCARDED, s.info(NS, "g1").orElseThrow().status());
    }
    // the rerun is a new generation from the pinned parent: every request first-seen again
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
      assertEquals(List.of(), s.abandonedOnOpen());
    }
    // a retry of the whole run from the same parent is now a CAS failure, not a duplicate publish
    TwoProcess.Result retry = TwoProcess.run(runArgs("g2"));
    assertEquals(3, retry.exit(), retry.output());
    assertTrue(retry.output().contains("CasException"), retry.output());
  }

  record CurrentPointer(@com.fasterxml.jackson.annotation.JsonProperty("current") String current) {}
}
