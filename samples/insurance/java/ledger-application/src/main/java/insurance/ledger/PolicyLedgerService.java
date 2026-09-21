package insurance.ledger;

import insurance.contract.v001.ContractV001;
import insurance.contract.v001.Evaluation;
import insurance.contract.v001.PolicyTable;
import insurance.ledger.generation.GenerationInfo;
import insurance.ledger.generation.GenerationStore;
import insurance.ledger.generation.GenerationStore.Applied;
import insurance.ledger.generation.GenerationStore.Lease;
import insurance.ledger.generation.ReceiptContext;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.TransactionRecord;
import java.util.List;
import java.util.Optional;

/**
 * Application service: the only place that combines the pure V001 contract with the durable
 * generation store. Every stateful path (batch CLI, raw HTTP, typed HTTP) goes through here.
 */
public final class PolicyLedgerService {
  private final ContractV001 contract;
  private final GenerationStore store;
  private final ReceiptContext receiptContext;

  public PolicyLedgerService(
      ContractV001 contract, GenerationStore store, ReceiptContext receiptContext) {
    this.contract = contract;
    this.store = store;
    this.receiptContext = receiptContext;
  }

  public GenerationStore store() {
    return store;
  }

  public ContractV001 contract() {
    return contract;
  }

  /** Bootstraps a namespace from supplied POLIN bytes pinned by an independent manifest. */
  public GenerationInfo bootstrap(
      String namespace, String generation, byte[] polin, ExpectedManifest manifest) {
    manifest.verifyInputs(polin, new byte[0]);
    List<PolicyRecord> masters = Records.policies(polin);
    return store.bootstrap(namespace, generation, masters);
  }

  /**
   * Opens a pending successor of {@code parent}. When {@code expectedPolin} is supplied, the
   * parent's published POLOUT bytes must equal it exactly; a mismatch refuses to seed.
   */
  public Lease begin(
      String namespace, String parent, String generation, Optional<byte[]> expectedPolin) {
    if (expectedPolin.isPresent()) {
      byte[] parentBytes = store.polout(namespace, parent);
      if (!java.util.Arrays.equals(parentBytes, expectedPolin.get())) {
        throw new GenerationStore.GenerationException(
            "published parent " + parent + " bytes differ from the supplied POLIN");
      }
    }
    return store.begin(namespace, parent, generation);
  }

  /** Applies one raw 40-byte request in arrival order and persists it under the lease. */
  public Applied apply(Lease lease, TransactionRecord request, boolean typed) {
    Optional<PolicyRecord> master =
        store.policy(lease.namespace(), lease.generation(), request.id());
    Evaluation evaluation =
        master.map(m -> contract.evaluate(m, request)).orElseGet(() -> contract.noPolicy(request));
    return store.commit(lease, request, evaluation, typed);
  }

  public Receipt publish(Lease lease, ExpectedManifest manifest, String stageMode) {
    ReceiptContext ctx =
        new ReceiptContext(
            stageMode,
            receiptContext.sourceCommit(),
            receiptContext.buildIdentity(),
            receiptContext.rateTableSha256());
    return store.publish(lease, manifest, ctx);
  }

  public void discard(Lease lease) {
    store.discard(lease);
  }

  /** Stateless evaluation for unit-style raw probes; never touches the store. */
  public Evaluation evaluateStateless(byte[] master128, byte[] txn40) {
    PolicyRecord master = new PolicyRecord(master128);
    TransactionRecord txn = new TransactionRecord(txn40);
    return contract.evaluate(master, txn);
  }

  public static boolean validMaster(byte[] master128) {
    try {
      PolicyTable.load(List.of(new PolicyRecord(master128)));
      return true;
    } catch (PolicyTable.BadMasterException e) {
      return false;
    }
  }
}
