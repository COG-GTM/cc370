package insurance.ledger.generation;

import insurance.contract.v001.Evaluation;
import insurance.contract.v001.PolicyTable;
import insurance.ledger.ContractBinding;
import insurance.ledger.Sha256;
import insurance.ledger.generation.GenerationStore.CheckpointException;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Establishes -- never assumes -- the durable prefix of a pending generation.
 *
 * <p>Given the pinned seed, the persisted entries and the persisted policy state, the verifier
 * checks that the ordinals are exactly {@code 1..lastOrdinal}, then replays the seed through the
 * running contract: for every entry the stored request is evaluated over the replayed master and
 * the produced result bytes and successor bytes must equal the stored ones byte for byte (an entry
 * whose stored result or successor the contract would not produce is a corrupt or incompatible
 * checkpoint, however self-consistent it is). The replayed policy state must equal the stored
 * state, the typed/raw admission counters must equal the per-entry typed flags, every entry must
 * carry its chain value and the chain must end at the stored checkpoint. Only a prefix that passes
 * all of this may be continued at {@code lastOrdinal + 1}; a prefix whose chain or checkpoint is
 * missing is not "unverifiable", it is rejected.
 *
 * <p>Chain: {@code c0 = sha256(seed)}, {@code ck = sha256(c(k-1) || ordinal(8 BE) || request(40) ||
 * result(96) || typed(1) || successor-flag(1) || successor(128 or empty))}. Each commit persists
 * {@code ck} with its entry and on the generation row, so the last commit, the entry stream and the
 * admission metadata of every entry are bound to each other.
 */
public final class PrefixVerifier {
  private PrefixVerifier() {}

  public record Entry(
      long ordinal, byte[] request, byte[] result, boolean typed, byte[] successor, String chain) {}

  public record Verified(
      byte[] state,
      byte[] results,
      byte[] requests,
      int typedRequests,
      int rawRequests,
      String checkpoint) {}

  public static String seedChain(byte[] seed) {
    return Sha256.of(seed);
  }

  public static String chain(
      String previous,
      long ordinal,
      byte[] request,
      byte[] result,
      boolean typed,
      byte[] successor) {
    if (previous == null) {
      throw new CheckpointException("no checkpoint to chain entry " + ordinal + " onto");
    }
    byte[] prev = previous.getBytes(StandardCharsets.US_ASCII);
    int successorLength = successor == null ? 0 : successor.length;
    ByteBuffer buf =
        ByteBuffer.allocate(prev.length + 8 + request.length + result.length + 2 + successorLength);
    buf.put(prev)
        .putLong(ordinal)
        .put(request)
        .put(result)
        .put((byte) (typed ? 1 : 0))
        .put((byte) (successor == null ? 0 : 1));
    if (successor != null) {
      buf.put(successor);
    }
    return Sha256.of(buf.array());
  }

  /**
   * @param seed the pinned parent bytes
   * @param entries persisted entries in ordinal order
   * @param lastOrdinal the generation row's counter
   * @param storedTyped the generation row's typed-request counter
   * @param storedRaw the generation row's raw-request counter
   * @param storedState persisted policy state in position order, or null when the store keeps no
   *     state separate from the entry stream
   * @param storedCheckpoint the generation row's checkpoint (chain of the last entry, or the seed
   *     chain when nothing was committed); required
   * @param binding the running contract
   * @throws CheckpointException on any disagreement or missing metadata; nothing is repaired
   */
  public static Verified verify(
      byte[] seed,
      List<Entry> entries,
      long lastOrdinal,
      int storedTyped,
      int storedRaw,
      byte[] storedState,
      String storedCheckpoint,
      ContractBinding binding) {
    if (storedCheckpoint == null) {
      throw new CheckpointException("generation has no checkpoint; nothing can be verified");
    }
    if (entries.size() != lastOrdinal) {
      throw new CheckpointException(
          "generation has " + entries.size() + " entries but last ordinal is " + lastOrdinal);
    }
    if ((long) storedTyped + storedRaw != lastOrdinal || storedTyped < 0 || storedRaw < 0) {
      throw new CheckpointException(
          "typed/raw counters "
              + storedTyped
              + "/"
              + storedRaw
              + " do not account for "
              + lastOrdinal
              + " committed requests");
    }
    int typed = 0;
    PolicyTable replay = PolicyTable.ofValidated(Records.policies(seed));
    byte[] results = new byte[entries.size() * ResultRecord.LENGTH];
    byte[] requests = new byte[entries.size() * TransactionRecord.LENGTH];
    String chain = seedChain(seed);
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      long expected = i + 1;
      if (e.ordinal() != expected) {
        throw new CheckpointException(
            "ordinal gap: expected " + expected + " found " + e.ordinal());
      }
      if (e.request().length != TransactionRecord.LENGTH
          || e.result().length != ResultRecord.LENGTH
          || (e.successor() != null && e.successor().length != PolicyRecord.LENGTH)) {
        throw new CheckpointException("entry " + expected + " has malformed record lengths");
      }
      TransactionRecord request = new TransactionRecord(e.request());
      Evaluation evaluation = binding.evaluate(replay.find(request.id()), request);
      if (!Arrays.equals(evaluation.result().bytes(), e.result())) {
        throw new CheckpointException(
            "entry " + expected + ": stored result is not what the contract produces");
      }
      byte[] successor = evaluation.accepted() ? evaluation.master().bytes() : null;
      if (!Arrays.equals(successor, e.successor())) {
        throw new CheckpointException(
            "entry " + expected + ": stored successor is not what the contract produces");
      }
      chain = chain(chain, expected, e.request(), e.result(), e.typed(), e.successor());
      if (e.chain() == null) {
        throw new CheckpointException("entry " + expected + ": no chain value is stored");
      }
      if (!chain.equals(e.chain())) {
        throw new CheckpointException("entry " + expected + ": hash chain broken");
      }
      if (e.typed()) {
        typed++;
      }
      if (evaluation.accepted()) {
        replay.replace(evaluation.master());
      }
      System.arraycopy(e.request(), 0, requests, i * TransactionRecord.LENGTH, 40);
      System.arraycopy(e.result(), 0, results, i * ResultRecord.LENGTH, ResultRecord.LENGTH);
    }
    byte[] replayed = replay.size() == 0 ? new byte[0] : Records.join(replay.rows());
    if (storedState != null && !Arrays.equals(storedState, replayed)) {
      throw new CheckpointException(
          "policy state disagrees with the contract replay of the committed prefix");
    }
    if (!chain.equals(storedCheckpoint)) {
      throw new CheckpointException("generation checkpoint does not match the entry chain");
    }
    if (typed != storedTyped) {
      throw new CheckpointException(
          "typed-request counter is "
              + storedTyped
              + " but "
              + typed
              + " entries carry the typed flag");
    }
    return new Verified(replayed, results, requests, typed, entries.size() - typed, chain);
  }
}
