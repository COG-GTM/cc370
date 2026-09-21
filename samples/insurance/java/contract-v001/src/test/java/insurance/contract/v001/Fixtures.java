package insurance.contract.v001;

import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Fullword;
import insurance.legacy.codec.Layout.Policy;
import insurance.legacy.codec.Layout.Transaction;
import insurance.legacy.codec.Packed;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.TransactionRecord;

final class Fixtures {
  private Fixtures() {}

  static byte[] id(String text) {
    return Cp037.encode(text);
  }

  static PolicyRecord master(
      String id, int issue, int date, int seq, long face, long cash, long loan, byte[] last) {
    byte[] raw = new byte[PolicyRecord.LENGTH];
    System.arraycopy(id(id), 0, raw, Policy.ID, 8);
    Fullword.put(raw, Policy.ISSUE, issue);
    Fullword.put(raw, Policy.DATE, date);
    Fullword.put(raw, Policy.SEQ, seq);
    Packed.put(raw, Policy.FACE, 7, face);
    Packed.put(raw, Policy.CASH, 7, cash);
    Packed.put(raw, Policy.LOAN, 7, loan);
    if (last != null) {
      System.arraycopy(last, 0, raw, Policy.LAST, Policy.LAST_LENGTH);
    }
    return new PolicyRecord(raw);
  }

  static PolicyRecord fresh(String id, int issue, long face, long cash, long loan) {
    return master(id, issue, issue, 0, face, cash, loan, null);
  }

  static TransactionRecord txn(String id, int seq, int date, char op, long amount) {
    return TransactionRecord.of(id(id), seq, date, Cp037.encode(String.valueOf(op))[0], amount);
  }

  static TransactionRecord withAmountBytes(TransactionRecord t, String hex) {
    byte[] raw = t.bytes();
    byte[] amt = insurance.legacy.codec.Hex.parse(hex);
    System.arraycopy(amt, 0, raw, Transaction.AMOUNT, 7);
    return new TransactionRecord(raw);
  }
}
