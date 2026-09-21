package insurance.ledger;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hash-bound receipt for one Java-produced generation. Every hash is recomputed by the parity
 * driver from the actual bytes; a receipt is evidence only when those recomputations agree. A JVM
 * has no MVS ABEND or step return-code semantics: {@code outcome} and {@code return_code} describe
 * the Java batch contract (INSBAT RC 0/12 emulation) and nothing else.
 */
public record Receipt(
    @JsonProperty("schema") String schema,
    @JsonProperty("mode") String mode,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("generation") String generation,
    @JsonProperty("parent_generation") String parentGeneration,
    @JsonProperty("stage") String stage,
    @JsonProperty("source_commit") String sourceCommit,
    @JsonProperty("build_identity") String buildIdentity,
    @JsonProperty("runtime_identity") String runtimeIdentity,
    @JsonProperty("record_schema") String recordSchema,
    @JsonProperty("rate_table_sha256") String rateTableSha256,
    @JsonProperty("expected_manifest_sha256") String expectedManifestSha256,
    @JsonProperty("polin_sha256") String polinSha256,
    @JsonProperty("txnin_sha256") String txninSha256,
    @JsonProperty("polout_sha256") String poloutSha256,
    @JsonProperty("resout_sha256") String resoutSha256,
    @JsonProperty("policies_count") int policiesCount,
    @JsonProperty("transactions_count") int transactionsCount,
    @JsonProperty("results_count") int resultsCount,
    @JsonProperty("typed_requests") int typedRequests,
    @JsonProperty("raw_requests") int rawRequests,
    @JsonProperty("return_code") int returnCode,
    @JsonProperty("outcome") String outcome,
    @JsonProperty("publication_status") String publicationStatus) {

  public static final String SCHEMA = "insurance-java-run-v1";
  public static final String RECORD_SCHEMA = "POLIN/POLOUT FB128, TXNIN FB40, RESOUT FB96, CP037";
}
