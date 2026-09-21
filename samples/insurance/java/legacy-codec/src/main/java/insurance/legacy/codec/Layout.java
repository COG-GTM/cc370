package insurance.legacy.codec;

/**
 * Byte offsets of the V001 records (copy/INSWORK.copy). Every byte of every record, including
 * reserved padding and tails, is part of the contract and is compared by the parity tools.
 */
public final class Layout {
  private Layout() {}

  /** POLIN/POLOUT master record (FB 128). */
  public static final class Policy {
    public static final int LENGTH = 128;
    public static final int ID = 0;
    public static final int ID_LENGTH = 8;
    public static final int ISSUE = 8;
    public static final int DATE = 12;
    public static final int SEQ = 16;
    public static final int FACE = 20;
    public static final int CASH = 27;
    public static final int LOAN = 34;
    public static final int AMOUNT_LENGTH = 7;
    public static final int PAD = 41;
    public static final int PAD_LENGTH = 7;
    public static final int LAST = 48;
    public static final int LAST_LENGTH = 40;
    public static final int TAIL = 88;
    public static final int TAIL_LENGTH = 40;

    private Policy() {}
  }

  /** TXNIN request record (FB 40). */
  public static final class Transaction {
    public static final int LENGTH = 40;
    public static final int ID = 0;
    public static final int ID_LENGTH = 8;
    public static final int SEQ = 8;
    public static final int DATE = 12;
    public static final int OP = 16;
    public static final int PAD = 17;
    public static final int PAD_LENGTH = 3;
    public static final int AMOUNT = 20;
    public static final int AMOUNT_LENGTH = 7;
    public static final int TAIL = 27;
    public static final int TAIL_LENGTH = 13;

    private Transaction() {}
  }

  /** RESOUT result record (FB 96). */
  public static final class Result {
    public static final int LENGTH = 96;
    public static final int ID = 0;
    public static final int ID_LENGTH = 8;
    public static final int SEQ = 8;
    public static final int DATE = 12;
    public static final int STATUS = 16;
    public static final int STATUS_LENGTH = 4;
    public static final int OP = 20;
    public static final int PAD = 21;
    public static final int PAD_LENGTH = 3;
    public static final int AGE = 24;
    public static final int RATE = 28;
    public static final int FEE = 31;
    public static final int BPS_LENGTH = 3;
    public static final int CASH = 34;
    public static final int SURRENDER = 41;
    public static final int DEATH = 48;
    public static final int LOAN = 55;
    public static final int INTEREST = 62;
    public static final int CHARGE = 69;
    public static final int AMOUNT_LENGTH = 7;
    public static final int VERSION = 76;
    public static final int VERSION_LENGTH = 4;
    public static final int TAIL = 80;
    public static final int TAIL_LENGTH = 16;

    private Result() {}
  }
}
