package insurance.legacy.codec;

import insurance.legacy.codec.Layout.Policy;

/** 128-byte POLIN/POLOUT master record. */
public final class PolicyRecord extends FixedRecord {
  public static final int LENGTH = Policy.LENGTH;

  public PolicyRecord(byte[] raw) {
    super(raw, LENGTH);
  }

  public static PolicyRecord zero() {
    return new PolicyRecord(new byte[LENGTH]);
  }

  public byte[] id() {
    return slice(Policy.ID, Policy.ID_LENGTH);
  }

  public int issue() {
    return fullword(Policy.ISSUE);
  }

  public int date() {
    return fullword(Policy.DATE);
  }

  public int seq() {
    return fullword(Policy.SEQ);
  }

  public byte[] faceRaw() {
    return slice(Policy.FACE, Policy.AMOUNT_LENGTH);
  }

  public byte[] cashRaw() {
    return slice(Policy.CASH, Policy.AMOUNT_LENGTH);
  }

  public byte[] loanRaw() {
    return slice(Policy.LOAN, Policy.AMOUNT_LENGTH);
  }

  public boolean faceValid() {
    return Packed.isValid(raw(), Policy.FACE, Policy.AMOUNT_LENGTH);
  }

  public boolean cashValid() {
    return Packed.isValid(raw(), Policy.CASH, Policy.AMOUNT_LENGTH);
  }

  public boolean loanValid() {
    return Packed.isValid(raw(), Policy.LOAN, Policy.AMOUNT_LENGTH);
  }

  /** Face amount in cents; requires {@link #faceValid()}. */
  public long face() {
    return Packed.value(raw(), Policy.FACE, Policy.AMOUNT_LENGTH);
  }

  public long cash() {
    return Packed.value(raw(), Policy.CASH, Policy.AMOUNT_LENGTH);
  }

  public long loan() {
    return Packed.value(raw(), Policy.LOAN, Policy.AMOUNT_LENGTH);
  }

  public byte[] pad() {
    return slice(Policy.PAD, Policy.PAD_LENGTH);
  }

  /** Raw bytes of the last accepted request (SLAST), compared byte-for-byte for DUPL/CNFL. */
  public byte[] last() {
    return slice(Policy.LAST, Policy.LAST_LENGTH);
  }

  public byte[] tail() {
    return slice(Policy.TAIL, Policy.TAIL_LENGTH);
  }

  /** Unsigned lexicographic comparison of the raw identifier bytes (the CLC used by INSBAT). */
  public static int compareIds(byte[] left, byte[] right) {
    return java.util.Arrays.compareUnsigned(left, right);
  }
}
