package insurance.legacy.codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/** Splits and joins fixed-length record streams. */
public final class Records {
  private Records() {}

  /** Raised when a byte stream is not a whole number of records (a partial trailing record). */
  public static final class PartialRecordException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final long actualLength;
    private final int recordLength;

    public PartialRecordException(String kind, long actualLength, int recordLength) {
      super(kind + ": " + actualLength + " bytes is not a multiple of LRECL " + recordLength);
      this.actualLength = actualLength;
      this.recordLength = recordLength;
    }

    public long actualLength() {
      return actualLength;
    }

    public int recordLength() {
      return recordLength;
    }
  }

  public static <R extends FixedRecord> List<R> split(
      byte[] raw, int recordLength, String kind, Function<byte[], R> factory) {
    if (raw.length % recordLength != 0) {
      throw new PartialRecordException(kind, raw.length, recordLength);
    }
    List<R> out = new ArrayList<>(raw.length / recordLength);
    for (int i = 0; i < raw.length; i += recordLength) {
      out.add(factory.apply(Arrays.copyOfRange(raw, i, i + recordLength)));
    }
    return out;
  }

  public static List<PolicyRecord> policies(byte[] raw) {
    return split(raw, PolicyRecord.LENGTH, "POLIN", PolicyRecord::new);
  }

  public static List<TransactionRecord> transactions(byte[] raw) {
    return split(raw, TransactionRecord.LENGTH, "TXNIN", TransactionRecord::new);
  }

  public static List<ResultRecord> results(byte[] raw) {
    return split(raw, ResultRecord.LENGTH, "RESOUT", ResultRecord::new);
  }

  public static byte[] join(List<? extends FixedRecord> records) {
    int total = 0;
    for (FixedRecord r : records) {
      total += r.length();
    }
    byte[] out = new byte[total];
    int at = 0;
    for (FixedRecord r : records) {
      byte[] b = r.bytes();
      System.arraycopy(b, 0, out, at, b.length);
      at += b.length;
    }
    return out;
  }
}
