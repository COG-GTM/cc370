package insurance.contract.v001;

import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Fullword;
import insurance.legacy.codec.Layout.Policy;
import insurance.legacy.codec.Layout.Result;
import insurance.legacy.codec.Layout.Transaction;
import insurance.legacy.codec.Packed;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Contract V001: {@code evaluate(master128, txn40) -> (master128', result96)}, the behaviour of
 * INSCALC with its INSVAL/INSPACK/INSDATE/INSRATE callees.
 *
 * <p>The implementation mirrors the assembler statement order so that the status precedence and
 * every byte written to the master and the result are the same. Amounts are integer cents; the
 * interest and surrender-charge products are computed on a {@link BigInteger} that is checked
 * against the declared PL12 work-field width.
 */
public final class ContractV001 {
  /** {@code MAXAMT DC PL7'99999999999'}: the largest accepted amount in cents. */
  public static final long MAX_AMOUNT = 99_999_999_999L;

  public static final byte[] VERSION = Cp037.encode("V001");

  private static final int WPROD_BYTES = 12;
  private static final BigInteger WPROD_MAX =
      BigInteger.TEN.pow(Packed.digits(WPROD_BYTES)).subtract(BigInteger.ONE);
  private static final BigInteger INTEREST_BIAS = BigInteger.valueOf(1_825_000L);
  private static final BigInteger INTEREST_DIVISOR = BigInteger.valueOf(3_650_000L);
  private static final BigInteger CHARGE_BIAS = BigInteger.valueOf(5_000L);
  private static final BigInteger CHARGE_DIVISOR = BigInteger.valueOf(10_000L);
  private static final int DIVISOR_BYTES = 5;
  private static final int QUOTIENT_BYTES = WPROD_BYTES - DIVISOR_BYTES;
  private static final long QUOTIENT_MAX = Packed.maxMagnitude(QUOTIENT_BYTES);
  private static final int RATE_BYTES = 3;
  private static final int DAYS_BYTES = 3;

  private static final byte OP_PREMIUM = Cp037.encode("P")[0];
  private static final byte OP_WITHDRAW = Cp037.encode("W")[0];
  private static final byte OP_LOAN = Cp037.encode("L")[0];
  private static final byte OP_REPAY = Cp037.encode("R")[0];
  private static final byte OP_QUOTE = Cp037.encode("Q")[0];
  private static final byte OP_DEATH = Cp037.encode("D")[0];

  private final RateTable rates;

  public ContractV001(RateTable rates) {
    this.rates = rates;
  }

  public static ContractV001 frozen() {
    return new ContractV001(RateTable.FROZEN);
  }

  public RateTable rates() {
    return rates;
  }

  /** Applies one request to one master. Never throws for data; throws only for width faults. */
  public Evaluation evaluate(PolicyRecord master, TransactionRecord txn) {
    byte[] before = master.bytes();
    byte[] s = master.bytes();
    byte[] t = txn.bytes();
    byte[] o = new byte[ResultRecord.LENGTH];

    System.arraycopy(t, Transaction.ID, o, Result.ID, Result.ID_LENGTH);
    System.arraycopy(t, Transaction.SEQ, o, Result.SEQ, 4);
    System.arraycopy(t, Transaction.DATE, o, Result.DATE, 4);
    o[Result.OP] = t[Transaction.OP];
    System.arraycopy(VERSION, 0, o, Result.VERSION, Result.VERSION_LENGTH);
    Packed.put(o, Result.RATE, Result.BPS_LENGTH, 0);
    Packed.put(o, Result.FEE, Result.BPS_LENGTH, 0);
    for (int off :
        new int[] {
          Result.CASH, Result.SURRENDER, Result.DEATH, Result.LOAN, Result.INTEREST, Result.CHARGE
        }) {
      Packed.put(o, off, Result.AMOUNT_LENGTH, 0);
    }

    if (!StateValidator.isValid(master)) {
      return exit(before, o, Status.STAT);
    }
    long cash = master.cash();
    long loan = master.loan();
    Packed.put(o, Result.CASH, Result.AMOUNT_LENGTH, cash);
    Packed.put(o, Result.LOAN, Result.AMOUNT_LENGTH, loan);

    if (!Arrays.equals(s, Policy.ID, Policy.ID + 8, t, Transaction.ID, Transaction.ID + 8)) {
      return exit(before, o, Status.NPOL);
    }
    int tseq = txn.seq();
    int sseq = master.seq();
    if (tseq <= 0 || tseq < sseq) {
      return exit(before, o, Status.ORDR);
    }
    if (tseq == sseq) {
      return exit(before, o, Arrays.equals(t, master.last()) ? Status.DUPL : Status.CNFL);
    }
    if (!txn.reservedZero()) {
      return exit(before, o, Status.FORM);
    }
    if (!txn.amountValid()) {
      return exit(before, o, Status.PACK);
    }
    long amount = txn.amount();
    if (amount < 0) {
      return exit(before, o, Status.NEGA);
    }
    if (amount > MAX_AMOUNT) {
      return exit(before, o, Status.OVER);
    }
    int tdate = txn.date();
    var tOrd = LegacyCalendar.ordinal(tdate);
    if (tOrd.isEmpty() || tdate < master.date()) {
      return exit(before, o, Status.DATE);
    }
    int age = LegacyCalendar.age(master.issue(), tdate);
    int days = tOrd.getAsInt() - LegacyCalendar.ordinal(master.date()).orElseThrow();
    RateTable.Row row = rates.lookup(tdate, age);

    // OVER: interest quotient or capitalized cash beyond MAXAMT.
    BigInteger prod = BigInteger.valueOf(cash);
    requireLeadingZeroBytes(prod, RATE_BYTES, "interest multiplicand");
    prod = prod.multiply(BigInteger.valueOf(row.rateBps()));
    requireLeadingZeroBytes(prod, DAYS_BYTES, "interest product");
    prod = prod.multiply(BigInteger.valueOf(days % 100_000)).add(INTEREST_BIAS);
    requireFits(prod, "interest dividend");
    BigInteger quotient = prod.divide(INTEREST_DIVISOR);
    if (quotient.compareTo(BigInteger.valueOf(QUOTIENT_MAX)) > 0) {
      throw new ContractDomainException(
          "interest quotient " + quotient + " exceeds PL" + QUOTIENT_BYTES);
    }
    long interest = quotient.longValueExact();
    if (interest > MAX_AMOUNT) {
      return fail(before, o, Status.OVER);
    }
    Packed.put(o, Result.INTEREST, Result.AMOUNT_LENGTH, interest);
    cash += interest;
    Packed.put(s, Policy.CASH, Policy.AMOUNT_LENGTH, cash);
    if (cash > MAX_AMOUNT) {
      return fail(before, o, Status.OVER);
    }

    byte op = txn.op();
    if (op == OP_PREMIUM) {
      cash += amount;
      Packed.put(s, Policy.CASH, Policy.AMOUNT_LENGTH, cash);
    } else if (op == OP_WITHDRAW) {
      cash -= amount;
      Packed.put(s, Policy.CASH, Policy.AMOUNT_LENGTH, cash);
    } else if (op == OP_LOAN) {
      loan += amount;
      Packed.put(s, Policy.LOAN, Policy.AMOUNT_LENGTH, loan);
    } else if (op == OP_REPAY) {
      loan -= amount;
      Packed.put(s, Policy.LOAN, Policy.AMOUNT_LENGTH, loan);
    } else if (op == OP_QUOTE || op == OP_DEATH) {
      if (amount != 0) {
        return fail(before, o, Status.AMNT);
      }
    } else {
      return fail(before, o, Status.TYPE);
    }

    if (cash < 0 || loan < 0 || loan > cash) {
      return fail(before, o, Status.FUND);
    }
    if (cash > MAX_AMOUNT || loan > MAX_AMOUNT) {
      return fail(before, o, Status.OVER);
    }

    BigInteger chargeProd = BigInteger.valueOf(cash);
    requireLeadingZeroBytes(chargeProd, RATE_BYTES, "charge multiplicand");
    chargeProd = chargeProd.multiply(BigInteger.valueOf(row.feeBps())).add(CHARGE_BIAS);
    requireFits(chargeProd, "charge dividend");
    BigInteger chargeQ = chargeProd.divide(CHARGE_DIVISOR);
    if (chargeQ.compareTo(BigInteger.valueOf(QUOTIENT_MAX)) > 0) {
      throw new ContractDomainException("charge quotient " + chargeQ + " exceeds PL7");
    }
    long charge = chargeQ.longValueExact();
    long surrender = Math.max(0L, cash - charge - loan);
    long face = master.face();
    long death = Math.max(0L, Math.max(face, cash) - loan);

    Packed.put(o, Result.CHARGE, Result.AMOUNT_LENGTH, charge);
    Packed.put(o, Result.SURRENDER, Result.AMOUNT_LENGTH, surrender);
    Packed.put(o, Result.DEATH, Result.AMOUNT_LENGTH, death);

    Fullword.put(s, Policy.SEQ, tseq);
    Fullword.put(s, Policy.DATE, tdate);
    System.arraycopy(t, 0, s, Policy.LAST, Policy.LAST_LENGTH);
    Fullword.put(o, Result.AGE, age);
    Packed.put(o, Result.RATE, Result.BPS_LENGTH, row.rateBps());
    Packed.put(o, Result.FEE, Result.BPS_LENGTH, row.feeBps());
    Packed.put(o, Result.CASH, Result.AMOUNT_LENGTH, cash);
    Packed.put(o, Result.LOAN, Result.AMOUNT_LENGTH, loan);
    System.arraycopy(Status.OKAY.bytes(), 0, o, Result.STATUS, Result.STATUS_LENGTH);
    return new Evaluation(new PolicyRecord(s), new ResultRecord(o), Status.OKAY);
  }

  /**
   * INSBAT's treatment of a request whose policy is not in the master table: INSCALC runs against
   * an all-zero master (which fails INSVAL) and the batch driver overwrites the status with NPOL.
   */
  public Evaluation noPolicy(TransactionRecord txn) {
    Evaluation e = evaluate(PolicyRecord.zero(), txn);
    byte[] o = e.result().bytes();
    System.arraycopy(Status.NPOL.bytes(), 0, o, Result.STATUS, Result.STATUS_LENGTH);
    return new Evaluation(PolicyRecord.zero(), new ResultRecord(o), Status.NPOL);
  }

  private static Evaluation exit(byte[] before, byte[] o, Status status) {
    System.arraycopy(status.bytes(), 0, o, Result.STATUS, Result.STATUS_LENGTH);
    return new Evaluation(new PolicyRecord(before), new ResultRecord(o), status);
  }

  private static Evaluation fail(byte[] before, byte[] o, Status status) {
    Packed.put(o, Result.INTEREST, Result.AMOUNT_LENGTH, 0);
    return exit(before, o, status);
  }

  private static void requireFits(BigInteger value, String what) {
    if (value.abs().compareTo(WPROD_MAX) > 0) {
      throw new ContractDomainException(what + " " + value + " exceeds PL" + WPROD_BYTES);
    }
  }

  /** MP requires the multiplicand to leave {@code multiplierBytes} leading zero bytes. */
  private static void requireLeadingZeroBytes(BigInteger value, int multiplierBytes, String what) {
    BigInteger limit =
        BigInteger.TEN.pow(Packed.digits(WPROD_BYTES - multiplierBytes)).subtract(BigInteger.ONE);
    if (value.abs().compareTo(limit) > 0) {
      throw new ContractDomainException(
          what + " " + value + " leaves fewer than " + multiplierBytes + " zero bytes");
    }
  }
}
