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

  /**
   * Bootstraps a namespace from supplied POLIN bytes pinned by an independent manifest. The
   * manifest must bind the running rate table and describe the raw POLIN bytes (count, hash, empty
   * TXNIN) before any master is parsed.
   */
  public GenerationInfo bootstrap(
      String namespace, String generation, byte[] polin, ExpectedManifest manifest) {
    manifest.verifyRates(receiptContext.rateTableSha256());
    manifest.verifyInputs(polin, new byte[0]);
    List<PolicyRecord> masters = Records.policies(polin);
    return store.bootstrap(namespace, generation, masters, manifest);
  }

  /**
   * Opens a pending successor of {@code parent} with its expected manifest pinned. The manifest
   * must bind the running rate table and describe the parent's published POLOUT bytes; when {@code
   * expectedPolin} is supplied, the parent's bytes must additionally equal it exactly. Any mismatch
   * refuses to seed and creates nothing.
   */
  public Lease begin(
      String namespace,
      String parent,
      String generation,
      Optional<byte[]> expectedPolin,
      ExpectedManifest manifest) {
    manifest.verifyRates(receiptContext.rateTableSha256());
    if (expectedPolin.isPresent()) {
      byte[] parentBytes = store.polout(namespace, parent);
      if (!java.util.Arrays.equals(parentBytes, expectedPolin.get())) {
        throw new GenerationStore.GenerationException(
            "published parent " + parent + " bytes differ from the supplied POLIN");
      }
      manifest.verifySeed(expectedPolin.get());
    }
    return store.begin(namespace, parent, generation, manifest);
  }

  /** Applies one raw 40-byte request in arrival order and persists it under the lease. */
  public Applied apply(Lease lease, TransactionRecord request, boolean typed) {
    return store.commit(lease, request, master -> evaluate(master, request), typed);
  }

  private Evaluation evaluate(Optional<PolicyRecord> master, TransactionRecord request) {
    return master
        .map(m -> contract.evaluate(m, request))
        .orElseGet(() -> contract.noPolicy(request));
  }

  /** Publishes against the manifest pinned at {@link #begin}; no manifest is accepted here. */
  public Receipt publish(Lease lease, String stageMode) {
    ReceiptContext ctx =
        new ReceiptContext(
            stageMode,
            receiptContext.sourceCommit(),
            receiptContext.buildIdentity(),
            receiptContext.rateTableSha256());
    return store.publish(lease, ctx);
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
