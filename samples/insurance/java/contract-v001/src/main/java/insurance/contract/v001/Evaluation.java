package insurance.contract.v001;

import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.ResultRecord;

/**
 * Outcome of one request: the successor master (byte-identical to the input master unless the
 * status is OKAY) and the 96-byte result.
 */
public record Evaluation(PolicyRecord master, ResultRecord result, Status status) {
  public boolean accepted() {
    return status.accepted();
  }
}
