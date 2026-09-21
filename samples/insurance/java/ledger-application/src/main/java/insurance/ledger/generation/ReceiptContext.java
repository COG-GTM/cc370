package insurance.ledger.generation;

import insurance.ledger.ExpectedManifest;
import insurance.ledger.Json;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;

/** Provenance that the store cannot know by itself; combined with the output hashes at publish. */
public record ReceiptContext(
    String mode, String sourceCommit, String buildIdentity, String rateTableSha256) {

  /**
   * The publication gate shared by every store: the pinned manifest must bind the running rate
   * table, describe the seed and the applied request stream exactly, and the produced outputs must
   * have the expected shape. Any problem throws and the caller discards the generation.
   */
  public void validatePublication(
      ExpectedManifest manifest, byte[] seed, byte[] requests, byte[] polout, byte[] resout) {
    manifest.verifyRates(rateTableSha256);
    manifest.verifyInputs(seed, requests);
    manifest.verifyOutputs(polout, resout, 0);
  }

  public Receipt complete(
      GenerationInfo info,
      ExpectedManifest manifest,
      byte[] seed,
      byte[] requests,
      byte[] polout,
      byte[] resout,
      String runtimeIdentity) {
    return new Receipt(
        Receipt.SCHEMA,
        mode,
        info.namespace(),
        info.generation(),
        info.parent(),
        manifest.stage(),
        sourceCommit,
        buildIdentity,
        runtimeIdentity,
        Receipt.RECORD_SCHEMA,
        rateTableSha256,
        manifestSha256(manifest),
        Sha256.of(seed),
        Sha256.of(requests),
        Sha256.of(polout),
        Sha256.of(resout),
        info.policiesCount(),
        manifest.transactionsCount(),
        resout.length / 96,
        info.typedRequests(),
        info.rawRequests(),
        0,
        "completed",
        GenerationStatus.PUBLISHED.name());
  }

  public static String manifestSha256(ExpectedManifest manifest) {
    return manifest.sourceSha256() != null
        ? manifest.sourceSha256()
        : Sha256.of(Json.bytes(manifest));
  }
}
