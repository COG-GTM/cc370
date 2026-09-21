package insurance.ledger.generation;

import insurance.ledger.ExpectedManifest;
import insurance.ledger.Json;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;

/** Provenance that the store cannot know by itself; combined with the output hashes at publish. */
public record ReceiptContext(
    String mode, String sourceCommit, String buildIdentity, String rateTableSha256) {

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
        manifest.sourceSha256() != null ? manifest.sourceSha256() : Sha256.of(Json.bytes(manifest)),
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
}
