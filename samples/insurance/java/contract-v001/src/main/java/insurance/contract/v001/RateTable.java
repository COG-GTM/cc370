package insurance.contract.v001;

import java.util.List;

/**
 * INSRATE: the frozen five-row rate table. The row in effect for a transaction is the latest row
 * whose effective date is at or before the transaction date; that single row applies to the whole
 * accrual interval. The fee is waived once the policy age reaches ten years.
 */
public final class RateTable {
  public static final int FEE_WAIVER_AGE = 10;

  /**
   * One table row: effective date, interest rate in basis points, surrender fee in basis points.
   */
  public record Row(int effective, int rateBps, int feeBps) {}

  /** The frozen rows of INSRATE.asm ({@code golden/v1/rates.json}). */
  public static final RateTable FROZEN =
      new RateTable(
          List.of(
              new Row(19000101, 125, 700),
              new Row(20000101, 175, 600),
              new Row(20200101, 225, 500),
              new Row(20240101, 300, 400),
              new Row(20250101, 325, 350)));

  private final List<Row> rows;

  public RateTable(List<Row> rows) {
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("rate table must have at least one row");
    }
    for (int i = 1; i < rows.size(); i++) {
      if (rows.get(i).effective() <= rows.get(i - 1).effective()) {
        throw new IllegalArgumentException("rate rows must be strictly ascending by date");
      }
    }
    for (Row r : rows) {
      if (r.rateBps() < 0 || r.rateBps() > 99999 || r.feeBps() < 0 || r.feeBps() > 99999) {
        throw new IllegalArgumentException("rate/fee must fit PL3 (0..99999)");
      }
    }
    this.rows = List.copyOf(rows);
  }

  public List<Row> rows() {
    return rows;
  }

  /** Rate and fee for a transaction date and policy age; the first row applies before any date. */
  public Row lookup(int transactionDate, int age) {
    Row selected = rows.get(0);
    for (Row r : rows) {
      if (transactionDate < r.effective()) {
        break;
      }
      selected = r;
    }
    int fee = age >= FEE_WAIVER_AGE ? 0 : selected.feeBps();
    return new Row(selected.effective(), selected.rateBps(), fee);
  }

  public int maxRateBps() {
    int max = 0;
    for (Row r : rows) {
      max = Math.max(max, r.rateBps());
    }
    return max;
  }
}
