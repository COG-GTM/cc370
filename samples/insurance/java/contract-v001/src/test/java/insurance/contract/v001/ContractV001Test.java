package insurance.contract.v001;

import static insurance.contract.v001.Fixtures.fresh;
import static insurance.contract.v001.Fixtures.txn;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import insurance.legacy.codec.Hex;
import insurance.legacy.codec.Layout.Policy;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractV001Test {
  private final ContractV001 contract = ContractV001.frozen();

  @Test
  void anchorA001Premium() {
    PolicyRecord m = fresh("00000001", 20240101, 1_000_000, 100_000, 0);
    TransactionRecord t = txn("00000001", 1, 20250101, 'P', 10_000);
    Evaluation e = contract.evaluate(m, t);
    ResultRecord r = e.result();
    assertEquals(Status.OKAY, e.status());
    assertEquals("OKAY", r.status());
    assertEquals(1, r.age());
    assertEquals(325, r.rate());
    assertEquals(350, r.fee());
    assertEquals(3259, r.interest());
    assertEquals(113_259, r.cash());
    assertEquals(3964, r.charge());
    assertEquals(109_295, r.surrender());
    assertEquals(1_000_000, r.death());
    assertEquals(0, r.loan());
    assertEquals("V001", r.version());
    assertEquals(1, e.master().seq());
    assertEquals(20250101, e.master().date());
    assertArrayEquals(t.bytes(), e.master().last());
    assertEquals(113_259, e.master().cash());
  }

  @Test
  void interestHalfUpTieRoundsAwayFromZero() {
    // 73000 * 325 * 1 = 23,725,000; /3,650,000 = 6.5 -> 7 (half-even would give 6).
    PolicyRecord m = fresh("00000002", 20250101, 0, 73_000, 0);
    Evaluation e = contract.evaluate(m, txn("00000002", 1, 20250102, 'Q', 0));
    assertEquals(Status.OKAY, e.status());
    assertEquals(7, e.result().interest());
    assertEquals(73_007, e.result().cash());
  }

  @Test
  void precedenceStatBeforeNpol() {
    byte[] raw = fresh("00000003", 20240101, 0, 0, 0).bytes();
    raw[Policy.TAIL] = 1;
    Evaluation e = contract.evaluate(new PolicyRecord(raw), txn("99999999", 1, 20240102, 'Q', 0));
    assertEquals(Status.STAT, e.status());
    assertEquals(0, e.result().cash());
    assertArrayEquals(raw, e.master().bytes());
  }

  @Test
  void npolOrdrCnflDupl() {
    PolicyRecord m = fresh("00000004", 20240101, 0, 500, 0);
    assertEquals(Status.NPOL, contract.evaluate(m, txn("00000005", 1, 20240102, 'Q', 0)).status());
    assertEquals(Status.ORDR, contract.evaluate(m, txn("00000004", 0, 20240102, 'Q', 0)).status());
    assertEquals(Status.ORDR, contract.evaluate(m, txn("00000004", -1, 20240102, 'Q', 0)).status());
    TransactionRecord first = txn("00000004", 3, 20240102, 'Q', 0);
    Evaluation ok = contract.evaluate(m, first);
    assertEquals(Status.OKAY, ok.status());
    assertEquals(Status.DUPL, contract.evaluate(ok.master(), first).status());
    assertEquals(
        Status.CNFL, contract.evaluate(ok.master(), txn("00000004", 3, 20240102, 'P', 1)).status());
    assertEquals(
        Status.ORDR, contract.evaluate(ok.master(), txn("00000004", 2, 20240102, 'Q', 0)).status());
    assertArrayEquals(ok.master().bytes(), contract.evaluate(ok.master(), first).master().bytes());
  }

  @Test
  void formPackNegaOverDate() {
    PolicyRecord m = fresh("00000006", 20240101, 0, 500, 0);
    byte[] form = txn("00000006", 1, 20240102, 'Q', 0).bytes();
    form[39] = 1;
    assertEquals(Status.FORM, contract.evaluate(m, new TransactionRecord(form)).status());
    assertEquals(
        Status.PACK,
        contract
            .evaluate(
                m, Fixtures.withAmountBytes(txn("00000006", 1, 20240102, 'P', 1), "000000000000a0"))
            .status());
    assertEquals(Status.NEGA, contract.evaluate(m, txn("00000006", 1, 20240102, 'P', -1)).status());
    assertEquals(
        Status.OVER,
        contract.evaluate(m, txn("00000006", 1, 20240102, 'P', 100_000_000_000L)).status());
    assertEquals(Status.DATE, contract.evaluate(m, txn("00000006", 1, 21000101, 'Q', 0)).status());
    assertEquals(Status.DATE, contract.evaluate(m, txn("00000006", 1, 20231231, 'Q', 0)).status());
    assertEquals(Status.DATE, contract.evaluate(m, txn("00000006", 1, 999990101, 'Q', 0)).status());
    assertEquals(Status.DATE, contract.evaluate(m, txn("00000006", 1, 19000229, 'Q', 0)).status());
  }

  @Test
  void typeAmntFundAfterInterest() {
    PolicyRecord m = fresh("00000007", 20240101, 0, 500, 0);
    Evaluation type = contract.evaluate(m, txn("00000007", 1, 20240102, 'X', 0));
    assertEquals(Status.TYPE, type.status());
    assertEquals(0, type.result().interest());
    assertEquals(500, type.result().cash());
    assertArrayEquals(m.bytes(), type.master().bytes());
    assertEquals(Status.AMNT, contract.evaluate(m, txn("00000007", 1, 20240102, 'Q', 1)).status());
    assertEquals(Status.AMNT, contract.evaluate(m, txn("00000007", 1, 20240102, 'D', 1)).status());
    assertEquals(
        Status.FUND, contract.evaluate(m, txn("00000007", 1, 20240102, 'W', 501)).status());
    assertEquals(
        Status.FUND, contract.evaluate(m, txn("00000007", 1, 20240102, 'L', 501)).status());
    assertEquals(Status.FUND, contract.evaluate(m, txn("00000007", 1, 20240102, 'R', 1)).status());
    assertEquals(
        Status.OKAY, contract.evaluate(m, txn("00000007", 1, 20240102, 'L', 500)).status());
  }

  @Test
  void negativeZeroAmountIsAcceptedAndPreservedInLast() {
    PolicyRecord m = fresh("00000008", 20240101, 0, 500, 0);
    TransactionRecord t =
        Fixtures.withAmountBytes(txn("00000008", 1, 20240102, 'Q', 0), "0000000000000d");
    Evaluation e = contract.evaluate(m, t);
    assertEquals(Status.OKAY, e.status());
    assertArrayEquals(t.bytes(), e.master().last());
    assertEquals("0000000000000d", Hex.of(e.master().last(), 20, 7));
  }

  @Test
  void unsignedInputSignsPassThroughOnRejectionAndUntouchedFields() {
    byte[] raw = fresh("00000009", 20240101, 700, 500, 100).bytes();
    raw[Policy.FACE + 6] = (byte) ((raw[Policy.FACE + 6] & 0xF0) | 0x0F);
    raw[Policy.LOAN + 6] = (byte) ((raw[Policy.LOAN + 6] & 0xF0) | 0x0F);
    PolicyRecord m = new PolicyRecord(raw);
    Evaluation rejected = contract.evaluate(m, txn("00000009", 1, 20240102, 'W', 9_999));
    assertEquals(Status.FUND, rejected.status());
    assertArrayEquals(raw, rejected.master().bytes());
    Evaluation ok = contract.evaluate(m, txn("00000009", 1, 20240102, 'P', 1));
    assertEquals(Status.OKAY, ok.status());
    assertArrayEquals(m.faceRaw(), ok.master().faceRaw());
    assertArrayEquals(m.loanRaw(), ok.master().loanRaw());
    assertEquals(0x0C, ok.master().cashRaw()[6] & 0x0F);
    assertEquals(100, ok.result().loan());
    assertEquals(0x0C, ok.result().bytes()[insurance.legacy.codec.Layout.Result.LOAN + 6] & 0x0F);
  }

  @Test
  void reachableQuotientOverUnderFrozenRates() {
    // cash=MAXAMT, issue=state=1900-01-01, txn 2025-01-01: 45,656 days at 325 bps.
    PolicyRecord m = fresh("00000010", 19000101, 0, ContractV001.MAX_AMOUNT, 0);
    Evaluation e = contract.evaluate(m, txn("00000010", 1, 20250101, 'Q', 0));
    assertEquals(Status.OVER, e.status());
    assertEquals(0, e.result().interest());
    assertEquals(ContractV001.MAX_AMOUNT, e.result().cash());
    assertArrayEquals(m.bytes(), e.master().bytes());
    assertEquals(45_656, LegacyCalendar.ordinal(20250101).getAsInt());
  }

  @Test
  void capitalizedCashOverIsDistinctFromQuotientOver() {
    PolicyRecord m = fresh("00000011", 20250101, 0, ContractV001.MAX_AMOUNT, 0);
    Evaluation e = contract.evaluate(m, txn("00000011", 1, 20250102, 'Q', 0));
    assertEquals(Status.OVER, e.status());
  }

  @Test
  void widthFaultFailsClosedInsteadOfLegacyStatus() {
    RateTable hot = new RateTable(List.of(new RateTable.Row(19000101, 99_999, 0)));
    ContractV001 c = new ContractV001(hot);
    PolicyRecord m = fresh("00000012", 19000101, 0, ContractV001.MAX_AMOUNT, 0);
    assertThrows(
        ContractDomainException.class, () -> c.evaluate(m, txn("00000012", 1, 20991231, 'Q', 0)));
  }

  @Test
  void deathQuoteDoesNotClosePolicyAndSurrenderFloorsAtZero() {
    PolicyRecord m = fresh("00000013", 20240101, 100, 1_000, 1_000);
    Evaluation e = contract.evaluate(m, txn("00000013", 1, 20240102, 'D', 0));
    assertEquals(Status.OKAY, e.status());
    assertEquals(0, e.result().surrender());
    assertEquals(0, e.result().death());
    assertEquals(1, e.master().seq());
    Evaluation next = contract.evaluate(e.master(), txn("00000013", 2, 20240103, 'Q', 0));
    assertEquals(Status.OKAY, next.status());
  }

  @Test
  void noPolicyResultEchoesRequestWithZeroAmounts() {
    TransactionRecord t = txn("00000099", 5, 20240102, 'P', 123);
    Evaluation e = contract.noPolicy(t);
    ResultRecord r = e.result();
    assertEquals(Status.NPOL, e.status());
    assertEquals("NPOL", r.status());
    assertEquals(5, r.seq());
    assertEquals(20240102, r.date());
    assertEquals(0, r.cash());
    assertEquals("V001", r.version());
  }

  @Test
  void calendarBounds() {
    assertEquals(73_048, LegacyCalendar.maxSpanDays());
    assertEquals(false, LegacyCalendar.isValid(19000229));
    assertEquals(true, LegacyCalendar.isValid(20000229));
    assertEquals(true, LegacyCalendar.isValid(20240229));
    assertEquals(false, LegacyCalendar.isValid(20230229));
    assertEquals(false, LegacyCalendar.isValid(18991231));
    assertEquals(false, LegacyCalendar.isValid(0));
    assertEquals(false, LegacyCalendar.isValid(-20240101));
    assertEquals(10, LegacyCalendar.age(20140615, 20240615));
    assertEquals(9, LegacyCalendar.age(20140615, 20240614));
  }
}
