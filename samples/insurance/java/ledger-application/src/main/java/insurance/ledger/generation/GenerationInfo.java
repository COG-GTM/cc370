package insurance.ledger.generation;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GenerationInfo(
    @JsonProperty("namespace") String namespace,
    @JsonProperty("generation") String generation,
    @JsonProperty("parent") String parent,
    @JsonProperty("status") GenerationStatus status,
    @JsonProperty("fence") long fence,
    @JsonProperty("policies_count") int policiesCount,
    @JsonProperty("last_ordinal") long lastOrdinal,
    @JsonProperty("typed_requests") int typedRequests,
    @JsonProperty("raw_requests") int rawRequests,
    @JsonProperty("seed_polin_sha256") String seedPolinSha256,
    @JsonProperty("expected_manifest_sha256") String expectedManifestSha256) {

  public GenerationInfo with(GenerationStatus newStatus) {
    return new GenerationInfo(
        namespace,
        generation,
        parent,
        newStatus,
        fence,
        policiesCount,
        lastOrdinal,
        typedRequests,
        rawRequests,
        seedPolinSha256,
        expectedManifestSha256);
  }

  public GenerationInfo advanced(boolean typed) {
    return new GenerationInfo(
        namespace,
        generation,
        parent,
        status,
        fence,
        policiesCount,
        lastOrdinal + 1,
        typedRequests + (typed ? 1 : 0),
        rawRequests + (typed ? 0 : 1),
        seedPolinSha256,
        expectedManifestSha256);
  }
}
