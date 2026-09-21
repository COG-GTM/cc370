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
 * state, and the hash chain over the entries must end at the stored checkpoint. Only a prefix that
 * passes all of this may be continued at {@code lastOrdinal + 1}.
 *
 * <p>Chain: {@code c0 = sha256(seed)}, {@code ck = sha256(c(k-1) || ordinal(8 BE) || request(40) ||
 * result(96) || flag(1) || successor(128 or empty))}. Each commit persists {@code ck} with its
 * entry and on the generation row, so the last commit and the entry stream are bound to each other.
 */
public final class PrefixVerifier {
  private PrefixVerifier() {}

  public record Entry(
      long ordinal, byte[] request, byte[] result, byte[] successor, String chain) {}

  public record Verified(byte[] state, byte[] results, byte[] requests, String checkpoint) {}

  public static String seedChain(byte[] seed) {
    return Sha256.of(seed);
  }

  public static String chain(
      String previous, long ordinal, byte[] request, byte[] result, byte[] successor) {
    byte[] prev = previous.getBytes(StandardCharsets.US_ASCII);
    int successorLength = successor == null ? 0 : successor.length;
    ByteBuffer buf =
        ByteBuffer.allocate(prev.length + 8 + request.length + result.length + 1 + successorLength);
    buf.put(prev).putLong(ordinal).put(request).put(result).put((byte) (successor == null ? 0 : 1));
    if (successor != null) {
      buf.put(successor);
    }
    return Sha256.of(buf.array());
  }

  /**
   * @param seed the pinned parent bytes
   * @param entries persisted entries in ordinal order
   * @param lastOrdinal the generation row's counter
   * @param storedState persisted policy state in position order, or null when the store keeps no
   *     state separate from the entry stream
   * @param storedCheckpoint the generation row's checkpoint (chain of the last entry, or the seed
   *     chain when nothing was committed), or null when only the per-entry chain is stored
   * @param binding the running contract
   * @throws CheckpointException on any disagreement; nothing is repaired
   */
  public static Verified verify(
      byte[] seed,
      List<Entry> entries,
      long lastOrdinal,
      byte[] storedState,
      String storedCheckpoint,
      ContractBinding binding) {
    if (entries.size() != lastOrdinal) {
      throw new CheckpointException(
          "generation has " + entries.size() + " entries but last ordinal is " + lastOrdinal);
    }
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
      chain = chain(chain, expected, e.request(), e.result(), e.successor());
      if (e.chain() != null && !chain.equals(e.chain())) {
        throw new CheckpointException("entry " + expected + ": hash chain broken");
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
    if (storedCheckpoint != null && !chain.equals(storedCheckpoint)) {
      throw new CheckpointException("generation checkpoint does not match the entry chain");
    }
    return new Verified(replayed, results, requests, chain);
  }
}
