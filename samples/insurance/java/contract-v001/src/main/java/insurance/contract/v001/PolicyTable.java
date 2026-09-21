package insurance.contract.v001;

import insurance.legacy.codec.Hex;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.TransactionRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * INSBAT's in-memory master table: at most 512 records, strictly ascending raw identifiers, every
 * record INSVAL-clean at load time. Requests are applied in physical order; an accepted request
 * replaces the policy's 128 bytes, a rejected one leaves them untouched, and a request naming an
 * unknown policy yields NPOL without touching the table.
 */
public final class PolicyTable {
  public static final int CAPACITY = 512;

  /** Raised at load time; corresponds to INSBAT return code 12 (no output written). */
  public static final class BadMasterException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int recordIndex;

    public BadMasterException(int recordIndex, String reason) {
      super("POLIN record " + recordIndex + ": " + reason);
      this.recordIndex = recordIndex;
    }

    public int recordIndex() {
      return recordIndex;
    }
  }

  private final List<PolicyRecord> rows;

  private PolicyTable(List<PolicyRecord> rows) {
    this.rows = rows;
  }

  public static PolicyTable load(List<PolicyRecord> masters) {
    List<PolicyRecord> rows = new ArrayList<>(masters.size());
    byte[] previous = new byte[8];
    for (int i = 0; i < masters.size(); i++) {
      PolicyRecord m = masters.get(i);
      if (i >= CAPACITY) {
        throw new BadMasterException(i, "more than " + CAPACITY + " master records");
      }
      if (PolicyRecord.compareIds(m.id(), previous) <= 0) {
        throw new BadMasterException(
            i, "identifier " + Hex.of(m.id()) + " not above " + Hex.of(previous));
      }
      if (!StateValidator.isValid(m)) {
        throw new BadMasterException(i, "INSVAL rejected master");
      }
      rows.add(m);
      previous = m.id();
    }
    return new PolicyTable(rows);
  }

  /** Reconstructs a table from already-validated rows (a published generation). */
  public static PolicyTable ofValidated(List<PolicyRecord> rows) {
    return load(rows);
  }

  public int size() {
    return rows.size();
  }

  public List<PolicyRecord> rows() {
    return Collections.unmodifiableList(rows);
  }

  public Optional<PolicyRecord> find(byte[] id) {
    for (PolicyRecord r : rows) {
      if (Arrays.equals(r.id(), id)) {
        return Optional.of(r);
      }
    }
    return Optional.empty();
  }

  private int indexOf(byte[] id) {
    for (int i = 0; i < rows.size(); i++) {
      if (Arrays.equals(rows.get(i).id(), id)) {
        return i;
      }
    }
    return -1;
  }

  /** Applies one request and, when accepted, stores the successor master. */
  public Evaluation apply(ContractV001 contract, TransactionRecord txn) {
    int idx = indexOf(txn.id());
    if (idx < 0) {
      return contract.noPolicy(txn);
    }
    Evaluation e = contract.evaluate(rows.get(idx), txn);
    if (e.accepted()) {
      rows.set(idx, e.master());
    }
    return e;
  }

  /** Replaces a policy's bytes (used by stores that persist state outside the table). */
  public void replace(PolicyRecord successor) {
    int idx = indexOf(successor.id());
    if (idx < 0) {
      throw new IllegalArgumentException("unknown policy " + Hex.of(successor.id()));
    }
    rows.set(idx, successor);
  }
}
