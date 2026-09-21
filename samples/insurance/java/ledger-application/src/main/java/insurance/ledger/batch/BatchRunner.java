package insurance.ledger.batch;

import insurance.contract.v001.ContractV001;
import insurance.contract.v001.Evaluation;
import insurance.contract.v001.PolicyTable;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * INSBAT as a pure function over byte streams: load the master table (RC 12 on a bad master, in
 * which case nothing is written), apply every TXNIN record in physical order, emit one RESOUT
 * record per request in the same order, and emit the whole table as POLOUT when it is non-empty.
 */
public final class BatchRunner {
  public static final int RC_SUCCESS = 0;
  public static final int RC_BAD_MASTER = 12;

  public record Output(
      int returnCode,
      byte[] polout,
      byte[] resout,
      List<Evaluation> evaluations,
      String badMasterReason) {
    public boolean success() {
      return returnCode == RC_SUCCESS;
    }
  }

  private final ContractV001 contract;

  public BatchRunner(ContractV001 contract) {
    this.contract = contract;
  }

  public Output run(byte[] polin, byte[] txnin) {
    List<PolicyRecord> masters = Records.policies(polin);
    List<TransactionRecord> txns = Records.transactions(txnin);
    PolicyTable table;
    try {
      table = PolicyTable.load(masters);
    } catch (PolicyTable.BadMasterException e) {
      return new Output(RC_BAD_MASTER, new byte[0], new byte[0], List.of(), e.getMessage());
    }
    List<Evaluation> evaluations = new ArrayList<>(txns.size());
    List<ResultRecord> results = new ArrayList<>(txns.size());
    for (TransactionRecord t : txns) {
      Evaluation e = table.apply(contract, t);
      evaluations.add(e);
      results.add(e.result());
    }
    byte[] polout = table.size() == 0 ? new byte[0] : Records.join(table.rows());
    return new Output(RC_SUCCESS, polout, Records.join(results), evaluations, null);
  }
}
