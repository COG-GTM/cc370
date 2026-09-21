package insurance.contract.v001;

import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Fullword;
import insurance.legacy.codec.Layout.Policy;
import insurance.legacy.codec.PolicyRecord;
import java.util.Arrays;

/** INSVAL: structural validation of a 128-byte master before any calculation. */
public final class StateValidator {
  private StateValidator() {}

  public static boolean isValid(PolicyRecord m) {
    byte[] raw = m.bytes();
    if (!Cp037.allDigits(raw, Policy.ID, Policy.ID_LENGTH)) {
      return false;
    }
    if (!m.isZero(Policy.PAD, Policy.PAD_LENGTH) || !m.isZero(Policy.TAIL, Policy.TAIL_LENGTH)) {
      return false;
    }
    if (m.seq() < 0) {
      return false;
    }
    if (!amountValid(m.faceValid(), m.faceValid() ? m.face() : 0)
        || !amountValid(m.cashValid(), m.cashValid() ? m.cash() : 0)
        || !amountValid(m.loanValid(), m.loanValid() ? m.loan() : 0)) {
      return false;
    }
    if (m.loan() > m.cash()) {
      return false;
    }
    if (!LegacyCalendar.isValid(m.issue()) || !LegacyCalendar.isValid(m.date())) {
      return false;
    }
    if (m.date() < m.issue()) {
      return false;
    }
    byte[] last = m.last();
    if (m.seq() == 0) {
      return m.isZero(Policy.LAST, Policy.LAST_LENGTH) && m.date() == m.issue();
    }
    return Arrays.equals(last, 0, 8, raw, Policy.ID, Policy.ID + 8)
        && Fullword.get(last, 8) == m.seq()
        && Fullword.get(last, 12) == m.date();
  }

  private static boolean amountValid(boolean packedOk, long value) {
    return packedOk && value >= 0 && value <= ContractV001.MAX_AMOUNT;
  }
}
