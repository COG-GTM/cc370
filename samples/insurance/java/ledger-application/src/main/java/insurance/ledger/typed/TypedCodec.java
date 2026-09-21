package insurance.ledger.typed;

import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Layout.Transaction;
import insurance.legacy.codec.Packed;
import insurance.legacy.codec.TransactionRecord;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bidirectional mapping between {@link TypedTransaction} and the 40-byte request.
 *
 * <p>Envelope grammar (HTTP 400 when violated, never a domain status): {@code id} is exactly eight
 * printable ASCII characters; {@code op} is one printable ASCII character; {@code date} is {@code
 * YYYY-MM-DD} with a four-digit year, month 01-12 and day 01-31 (calendar validity and the
 * 1900-2099 domain are the contract's business, so {@code 2100-01-01} encodes and yields {@code
 * DATE}); {@code amount} is signed cents whose magnitude fits the 13 digits of a PL7 ({@code 10^12}
 * is representable and yields {@code OVER}; {@code 10^13} is an envelope failure).
 *
 * <p>Encoding writes zero padding/tail and the preferred sign nibbles ({@code C} for zero and
 * positive, {@code D} for negative). {@link #decode} therefore succeeds only when re-encoding the
 * decoded fields reproduces the original 40 bytes exactly: {@code F} signs, negative zero, nonzero
 * reserved bytes, malformed packed digits, non-round-tripping identifiers and dates outside the
 * grammar are all reported as not typed and must be sent raw.
 */
public final class TypedCodec {
  private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
  public static final long MAX_TYPED_AMOUNT = Packed.maxMagnitude(Transaction.AMOUNT_LENGTH);

  private TypedCodec() {}

  public static TransactionRecord encode(TypedTransaction t) {
    if (t.id() == null || !printable(t.id(), Transaction.ID_LENGTH)) {
      throw new TypedEnvelopeException("id must be exactly 8 printable ASCII characters");
    }
    if (t.op() == null || !printable(t.op(), 1)) {
      throw new TypedEnvelopeException("op must be exactly 1 printable ASCII character");
    }
    int date = encodeDate(t.date());
    if (Math.abs(t.amount()) > MAX_TYPED_AMOUNT || t.amount() == Long.MIN_VALUE) {
      throw new TypedEnvelopeException(
          "amount magnitude exceeds the 13 decimal digits of the request field");
    }
    return TransactionRecord.of(
        Cp037.encode(t.id()), t.seq(), date, Cp037.encode(t.op())[0], t.amount());
  }

  /** Typed view of {@code r}, present only when {@code encode(view).bytes()} equals {@code r}. */
  public static Optional<TypedTransaction> decode(TransactionRecord r) {
    byte[] raw = r.bytes();
    if (!Cp037.roundTrips(raw, Transaction.ID, Transaction.ID_LENGTH)
        || !Cp037.roundTrips(raw, Transaction.OP, 1)
        || !r.amountValid()) {
      return Optional.empty();
    }
    Optional<String> date = isoDate(r.date());
    if (date.isEmpty()) {
      return Optional.empty();
    }
    TypedTransaction view =
        new TypedTransaction(
            Cp037.decode(raw, Transaction.ID, Transaction.ID_LENGTH),
            r.seq(),
            date.get(),
            Cp037.decode(raw, Transaction.OP, 1),
            r.amount());
    try {
      TransactionRecord again = encode(view);
      return Arrays.equals(again.bytes(), raw) ? Optional.of(view) : Optional.empty();
    } catch (TypedEnvelopeException e) {
      return Optional.empty();
    }
  }

  /** {@code YYYY-MM-DD} for a yyyymmdd fullword inside the envelope grammar, else empty. */
  public static Optional<String> isoDate(int yyyymmdd) {
    if (yyyymmdd < 0) {
      return Optional.empty();
    }
    int y = yyyymmdd / 10000;
    int m = (yyyymmdd / 100) % 100;
    int d = yyyymmdd % 100;
    if (y > 9999 || m < 1 || m > 12 || d < 1 || d > 31) {
      return Optional.empty();
    }
    return Optional.of(String.format("%04d-%02d-%02d", y, m, d));
  }

  static int encodeDate(String iso) {
    if (iso == null) {
      throw new TypedEnvelopeException("date is required");
    }
    var m = DATE.matcher(iso);
    if (!m.matches()) {
      throw new TypedEnvelopeException("date must match YYYY-MM-DD");
    }
    int year = Integer.parseInt(m.group(1));
    int month = Integer.parseInt(m.group(2));
    int day = Integer.parseInt(m.group(3));
    if (month < 1 || month > 12 || day < 1 || day > 31) {
      throw new TypedEnvelopeException("date month must be 01-12 and day 01-31");
    }
    return year * 10000 + month * 100 + day;
  }

  private static boolean printable(String s, int length) {
    if (s.length() != length) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x20 || c > 0x7E) {
        return false;
      }
    }
    return true;
  }
}
