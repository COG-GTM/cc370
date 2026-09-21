package insurance.legacy.codec;

import insurance.legacy.codec.Layout.Transaction;

/** 40-byte TXNIN request record. */
public final class TransactionRecord extends FixedRecord {
  public static final int LENGTH = Transaction.LENGTH;

  public TransactionRecord(byte[] raw) {
    super(raw, LENGTH);
  }

  public byte[] id() {
    return slice(Transaction.ID, Transaction.ID_LENGTH);
  }

  public int seq() {
    return fullword(Transaction.SEQ);
  }

  public int date() {
    return fullword(Transaction.DATE);
  }

  public byte op() {
    return raw()[Transaction.OP];
  }

  public byte[] pad() {
    return slice(Transaction.PAD, Transaction.PAD_LENGTH);
  }

  public byte[] amountRaw() {
    return slice(Transaction.AMOUNT, Transaction.AMOUNT_LENGTH);
  }

  public boolean amountValid() {
    return Packed.isValid(raw(), Transaction.AMOUNT, Transaction.AMOUNT_LENGTH);
  }

  /** Amount in cents; requires {@link #amountValid()}. Negative zero decodes as 0. */
  public long amount() {
    return Packed.value(raw(), Transaction.AMOUNT, Transaction.AMOUNT_LENGTH);
  }

  public int amountSign() {
    return Packed.signNibble(raw(), Transaction.AMOUNT, Transaction.AMOUNT_LENGTH);
  }

  public byte[] tail() {
    return slice(Transaction.TAIL, Transaction.TAIL_LENGTH);
  }

  /** FORM rule: reserved padding and tail bytes must all be zero. */
  public boolean reservedZero() {
    return isZero(Transaction.PAD, Transaction.PAD_LENGTH)
        && isZero(Transaction.TAIL, Transaction.TAIL_LENGTH);
  }

  /** Assembles a request from typed fields with preferred sign; used by the typed HTTP path. */
  public static TransactionRecord of(byte[] id, int seq, int date, byte op, long amountCents) {
    if (id.length != Transaction.ID_LENGTH) {
      throw new IllegalArgumentException("id must be 8 bytes");
    }
    byte[] raw = new byte[LENGTH];
    System.arraycopy(id, 0, raw, Transaction.ID, Transaction.ID_LENGTH);
    Fullword.put(raw, Transaction.SEQ, seq);
    Fullword.put(raw, Transaction.DATE, date);
    raw[Transaction.OP] = op;
    Packed.put(raw, Transaction.AMOUNT, Transaction.AMOUNT_LENGTH, amountCents);
    return new TransactionRecord(raw);
  }
}
