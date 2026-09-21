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

  record Lease(String namespace, String generation, String parent, long fence) {}

  record Applied(long ordinal, Evaluation evaluation) {}

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

  void discard(Lease lease);

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

  List<String> namespaces();

  @Override
  void close();
}
