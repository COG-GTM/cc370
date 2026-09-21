package insurance.ledger.generation;

import insurance.contract.v001.Evaluation;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.Receipt;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.TransactionRecord;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Durable generation ledger. A namespace has at most one current generation. Generations are
 * bootstrapped (published root without parent) or begun as pending successors seeded from a
 * published parent, each with an independently pinned {@link ExpectedManifest} supplied at creation
 * time. A pending generation has exactly one fenced writer; every applied request is persisted
 * atomically with its successor state under a contiguous serial ordinal. Publication validates
 * against the pinned manifest, flips the status and moves the namespace pointer with an
 * expected-parent CAS under one lock; published rows are never modified afterwards.
 *
 * <p>Recovery: a pending generation whose writer is gone (lease expired) is neither served nor
 * silently discarded. It may be {@link #claim claimed} by a new writer, which atomically takes the
 * ownership (new fence) only after establishing the durable prefix with {@link PrefixVerifier}
 * against the pinned seed, manifest, rate table and contract identity, and then continues at {@code
 * lastOrdinal + 1}; or it may be explicitly {@link #discardAbandoned discarded}. A claim never
 * repairs, truncates or reruns anything: a prefix that does not verify fails closed.
 *
 * <p>Closing a store releases whatever exclusive resource it holds (an OS file lock, a writer lease
 * heartbeat); it does not discard or publish anything.
 */
public interface GenerationStore extends AutoCloseable {

  /** Publication or lifecycle rule violation (not a legacy domain outcome). */
  class GenerationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GenerationException(String message) {
      super(message);
    }
  }

  /** Raised when an operation is attempted with a stale fence token. */
  final class FencedException extends GenerationException {
    private static final long serialVersionUID = 1L;

    public FencedException(String message) {
      super(message);
    }
  }

  /** Raised when the expected parent is no longer the namespace's current generation. */
  final class CasException extends GenerationException {
    private static final long serialVersionUID = 1L;

    public CasException(String message) {
      super(message);
    }
  }

  /** The durable prefix did not verify or is incompatible with the running contract. */
  final class CheckpointException extends GenerationException {
    private static final long serialVersionUID = 1L;

    public CheckpointException(String message) {
      super(message);
    }
  }

  record Lease(String namespace, String generation, String parent, long fence) {}

  record Applied(long ordinal, Evaluation evaluation) {}

  /**
   * Result of a successful claim: the new lease, how many times the generation has been claimed,
   * the verified prefix length ({@code lastOrdinal}; the writer continues at {@code lastOrdinal +
   * 1}), the hash of the committed request bytes (so a resuming client can check that the prefix is
   * its own input before skipping it) and the verified checkpoint.
   */
  record Claimed(
      Lease lease,
      long claims,
      long lastOrdinal,
      int typedRequests,
      int rawRequests,
      String committedRequestsSha256,
      String checkpoint) {}

  /**
   * Publishes a root generation from validated masters. The manifest must already have been checked
   * against the raw POLIN bytes and the running rate table; it is pinned with the root.
   */
  GenerationInfo bootstrap(
      String namespace, String generation, List<PolicyRecord> masters, ExpectedManifest manifest);

  /**
   * Opens a pending successor of {@code parent} and pins {@code manifest} to it. The manifest must
   * describe the parent's published bytes (count and hash) and bind the running rate table;
   * otherwise nothing is created.
   */
  Lease begin(String namespace, String parent, String generation, ExpectedManifest manifest);

  /** Resolves the current bytes of a policy inside a pending or published generation. */
  Optional<PolicyRecord> policy(String namespace, String generation, byte[] id);

  /**
   * Under the fenced generation lock: loads the current master for {@code request.id()}, hands it
   * to {@code evaluator} (empty when the policy is absent) and persists request, result, successor
   * state and the next contiguous ordinal atomically. Evaluating inside the lock is what makes a
   * read-evaluate-persist sequence serial with every other writer of the generation.
   */
  Applied commit(
      Lease lease,
      TransactionRecord request,
      Function<Optional<PolicyRecord>, Evaluation> evaluator,
      boolean typed);

  /**
   * Under the generation lock: verify fence, rebuild outputs, validate seed, applied requests,
   * outputs and rate binding against the manifest pinned at {@link #begin}, write the receipt, flip
   * the status and move the namespace pointer with an expected-parent CAS. On any failure the
   * generation is discarded, never published.
   */
  Receipt publish(Lease lease, ReceiptContext context);

  /** The manifest pinned to a generation of any status. */
  Optional<ExpectedManifest> manifest(String namespace, String generation);

  /**
   * Takes over an abandoned pending generation. Under the generation lock: the generation must be
   * PENDING with an expired writer lease (a live writer is never displaced), {@code manifest} must
   * be the pinned manifest byte for byte and bind the running rate table, the pinned contract
   * identity must be the running one, and the durable prefix must verify ({@link PrefixVerifier}).
   * Only then are fence and writer replaced atomically. Any failure leaves the generation exactly
   * as it was (still claimable or discardable) and throws.
   */
  Claimed claim(String namespace, String generation, ExpectedManifest manifest);

  /** Discards under the caller's live lease. */
  void discard(Lease lease);

  /**
   * Explicit operator discard of a pending generation whose writer lease has expired. Refused while
   * the lease is live (the writer, or a successful claimant, owns it).
   */
  void discardAbandoned(String namespace, String generation, String reason);

  Optional<String> current(String namespace);

  Optional<GenerationInfo> info(String namespace, String generation);

  /** Published generations only; reachable from the accepted current ancestry. */
  byte[] polout(String namespace, String generation);

  byte[] resout(String namespace, String generation);

  byte[] requests(String namespace, String generation);

  Optional<Receipt> receipt(String namespace, String generation);

  /** Explicit peek at an unvalidated pending generation; never served as accepted output. */
  byte[] peekPolout(String namespace, String generation);

  byte[] peekResout(String namespace, String generation);

  byte[] peekRequests(String namespace, String generation);

  List<String> namespaces();

  @Override
  void close();
}
