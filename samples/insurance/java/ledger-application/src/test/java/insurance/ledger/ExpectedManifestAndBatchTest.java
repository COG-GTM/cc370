package insurance.ledger;

import static insurance.ledger.LedgerFixtures.a001;
import static insurance.ledger.LedgerFixtures.concat;
import static insurance.ledger.LedgerFixtures.fresh;
import static insurance.ledger.LedgerFixtures.manifest;
import static insurance.ledger.LedgerFixtures.txn;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import insurance.contract.v001.ContractV001;
import insurance.ledger.ExpectedManifest.ManifestException;
import insurance.ledger.batch.BatchRunner;
import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpectedManifestAndBatchTest {
  private final BatchRunner runner = new BatchRunner(ContractV001.frozen());

  private static byte[] twoPolicies() {
    return concat(a001().bytes(), fresh("00000002", 500_000, 20_000, 0).bytes());
  }

  private static byte[] threeTxns() {
    return concat(
        txn("00000001", 1, LedgerFixtures.VALUATION, 'P', 10_000).bytes(),
        txn("00000002", 1, LedgerFixtures.VALUATION, 'Q', 0).bytes(),
        txn("00000009", 1, LedgerFixtures.VALUATION, 'P', 1).bytes());
  }

  @Test
  void manifestAcceptsExactInputs() {
    manifest("t", 2, 3, twoPolicies(), threeTxns()).verifyInputs(twoPolicies(), threeTxns());
  }

  @Test
  void manifestAcceptsGenuinelyEmptyInputs() {
    manifest("empty", 0, 0, new byte[0], new byte[0]).verifyInputs(new byte[0], new byte[0]);
  }

  @Test
  void manifestRejectsAlignedTruncation() {
    byte[] pol = twoPolicies();
    byte[] truncated = Arrays.copyOf(pol, 128);
    ManifestException e =
        assertThrows(
            ManifestException.class,
            () -> manifest("t", 2, 3, pol, threeTxns()).verifyInputs(truncated, threeTxns()));
    assertTrue(
        e.problems().get(0).contains("aligned truncation or missing records"), e::getMessage);
    assertTrue(e.problems().get(1).contains("sha256"), e::getMessage);
  }

  @Test
  void manifestRejectsPartialRecordAndUnexpectedlyEmptyDelivery() {
    byte[] txns = threeTxns();
    ExpectedManifest m = manifest("t", 2, 3, twoPolicies(), txns);
    ManifestException partial =
        assertThrows(
            ManifestException.class,
            () -> m.verifyInputs(twoPolicies(), Arrays.copyOf(txns, txns.length - 7)));
    assertTrue(partial.problems().get(0).contains("partial record"), partial::getMessage);
    ManifestException empty =
        assertThrows(ManifestException.class, () -> m.verifyInputs(twoPolicies(), new byte[0]));
    assertTrue(empty.problems().get(0).contains("0 complete records, expected 3"));
  }

  @Test
  void manifestRejectsWrongBytesOfRightLength() {
    byte[] txns = threeTxns();
    byte[] mutated = txns.clone();
    mutated[20] ^= 0x10;
    ManifestException e =
        assertThrows(
            ManifestException.class,
            () -> manifest("t", 2, 3, twoPolicies(), txns).verifyInputs(twoPolicies(), mutated));
    assertEquals(1, e.problems().size());
    assertTrue(e.problems().get(0).startsWith("TXNIN: sha256"));
  }

  @Test
  void manifestRejectsWrongOutputCountsAndReturnCode() {
    ExpectedManifest m = manifest("t", 2, 3, twoPolicies(), threeTxns());
    assertThrows(ManifestException.class, () -> m.verifyOutputs(twoPolicies(), new byte[96], 0));
    assertThrows(
        ManifestException.class, () -> m.verifyOutputs(twoPolicies(), new byte[96 * 3], 12));
    m.verifyOutputs(twoPolicies(), new byte[96 * 3], 0);
  }

  @Test
  void batchPreservesPhysicalOrderAndEmitsOneResultPerTransaction() {
    BatchRunner.Output out = runner.run(twoPolicies(), threeTxns());
    assertTrue(out.success());
    List<ResultRecord> results = Records.results(out.resout());
    assertEquals(3, results.size());
    assertEquals(
        List.of("OKAY", "OKAY", "NPOL"), results.stream().map(ResultRecord::status).toList());
    assertEquals("00000009", Cp037.decode(results.get(2).id(), 0, 8));
    assertEquals(2 * 128, out.polout().length);
    assertEquals("00000001", Cp037.decode(Records.policies(out.polout()).get(0).id(), 0, 8));
  }

  @Test
  void batchEmptyTransactionsLeavesMasterUnchangedWithZeroResults() {
    BatchRunner.Output out = runner.run(twoPolicies(), new byte[0]);
    assertTrue(out.success());
    assertEquals(0, out.resout().length);
    assertArrayEquals(twoPolicies(), out.polout());
  }

  @Test
  void batchEmptyMasterYieldsNpolForEveryTransactionAndEmptyPolout() {
    BatchRunner.Output out = runner.run(new byte[0], threeTxns());
    assertTrue(out.success());
    assertEquals(0, out.polout().length);
    assertEquals(
        List.of("NPOL", "NPOL", "NPOL"),
        Records.results(out.resout()).stream().map(ResultRecord::status).toList());
  }

  @Test
  void batchRejectsInvalidMasterWithRc12AndNoOutput() {
    byte[] bad = twoPolicies();
    bad[128 + 41] = 0x01; // SPAD of the second master must be zero
    BatchRunner.Output out = runner.run(bad, threeTxns());
    assertEquals(BatchRunner.RC_BAD_MASTER, out.returnCode());
    assertEquals(0, out.polout().length);
    assertEquals(0, out.resout().length);
  }

  @Test
  void batchRejectsPartialMasterRecord() {
    byte[] pol = twoPolicies();
    assertThrows(
        Records.PartialRecordException.class,
        () -> runner.run(Arrays.copyOf(pol, pol.length - 1), threeTxns()));
  }
}
