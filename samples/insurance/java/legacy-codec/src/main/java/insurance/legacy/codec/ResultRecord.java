package insurance.legacy.codec;

import insurance.legacy.codec.Layout.Result;

/** 96-byte RESOUT result record. */
public final class ResultRecord extends FixedRecord {
  public static final int LENGTH = Result.LENGTH;

  public ResultRecord(byte[] raw) {
    super(raw, LENGTH);
  }

  public byte[] id() {
    return slice(Result.ID, Result.ID_LENGTH);
  }

  public int seq() {
    return fullword(Result.SEQ);
  }

  public int date() {
    return fullword(Result.DATE);
  }

  public byte[] statusRaw() {
    return slice(Result.STATUS, Result.STATUS_LENGTH);
  }

  public String status() {
    return Cp037.decode(raw(), Result.STATUS, Result.STATUS_LENGTH);
  }

  public byte op() {
    return raw()[Result.OP];
  }

  public byte[] pad() {
    return slice(Result.PAD, Result.PAD_LENGTH);
  }

  public int age() {
    return fullword(Result.AGE);
  }

  public long rate() {
    return Packed.value(raw(), Result.RATE, Result.BPS_LENGTH);
  }

  public long fee() {
    return Packed.value(raw(), Result.FEE, Result.BPS_LENGTH);
  }

  public long cash() {
    return Packed.value(raw(), Result.CASH, Result.AMOUNT_LENGTH);
  }

  public long surrender() {
    return Packed.value(raw(), Result.SURRENDER, Result.AMOUNT_LENGTH);
  }

  public long death() {
    return Packed.value(raw(), Result.DEATH, Result.AMOUNT_LENGTH);
  }

  public long loan() {
    return Packed.value(raw(), Result.LOAN, Result.AMOUNT_LENGTH);
  }

  public long interest() {
    return Packed.value(raw(), Result.INTEREST, Result.AMOUNT_LENGTH);
  }

  public long charge() {
    return Packed.value(raw(), Result.CHARGE, Result.AMOUNT_LENGTH);
  }

  public String version() {
    return Cp037.decode(raw(), Result.VERSION, Result.VERSION_LENGTH);
  }

  public byte[] tail() {
    return slice(Result.TAIL, Result.TAIL_LENGTH);
  }
}
