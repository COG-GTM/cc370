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
 * published parent. A pending generation has exactly one fenced writer; every applied request is
 * persisted atomically with its successor state under a contiguous serial ordinal. Publication
 * validates, flips the status and moves the namespace pointer with an expected-parent CAS under one
 * lock; published rows are never modified afterwards.
 */
public interface GenerationStore {

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

  GenerationInfo bootstrap(String namespace, String generation, List<PolicyRecord> masters);

  Lease begin(String namespace, String parent, String generation);

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
   * Under the generation lock: verify fence, rebuild outputs, validate them against the pinned
   * manifest, write the receipt, flip the status and move the namespace pointer with an
   * expected-parent CAS. On any failure the generation is discarded, never published.
   */
  Receipt publish(Lease lease, ExpectedManifest manifest, ReceiptContext context);

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
}
