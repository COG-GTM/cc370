package insurance.app.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import insurance.ledger.typed.TypedResult;
import insurance.ledger.typed.TypedTransaction;

/**
 * Wire types of the stateful HTTP API. Bulk legacy files travel as base64 ({@code *Base64}), single
 * records as hex ({@code *Hex}); JSON field values are never a substitute for the bytes. Expected
 * manifests travel as the exact bytes of the pinned manifest file ({@code manifestBase64}) so the
 * receipt binds the caller's document, not a re-serialisation.
 */
public final class Api {
  private Api() {}

  public record ImportRequest(
      @JsonProperty("generation") String generation,
      @JsonProperty("polinBase64") String polinBase64,
      @JsonProperty("manifestBase64") String manifestBase64) {}

  public record BeginRequest(
      @JsonProperty("parent") String parent,
      @JsonProperty("generation") String generation,
      @JsonProperty("polinBase64") String polinBase64) {}

  public record LeaseResponse(
      @JsonProperty("namespace") String namespace,
      @JsonProperty("generation") String generation,
      @JsonProperty("parent") String parent,
      @JsonProperty("fence") long fence) {}

  public record RawRequest(
      @JsonProperty("fence") long fence, @JsonProperty("recordHex") String recordHex) {}

  public record TypedRequest(
      @JsonProperty("fence") long fence, @JsonProperty("request") TypedTransaction request) {}

  /**
   * Response of both request endpoints. {@code requestHex} echoes the exact 40 bytes the contract
   * evaluated (for typed requests: the server-side encoding), {@code resultHex} the 96 result
   * bytes, and {@code result} the typed decode of those same bytes.
   */
  public record ApplyResponse(
      @JsonProperty("ordinal") long ordinal,
      @JsonProperty("typed") boolean typed,
      @JsonProperty("status") String status,
      @JsonProperty("accepted") boolean accepted,
      @JsonProperty("requestHex") String requestHex,
      @JsonProperty("resultHex") String resultHex,
      @JsonProperty("result") TypedResult result) {}

  public record BatchRequest(
      @JsonProperty("fence") long fence, @JsonProperty("txninBase64") String txninBase64) {}

  public record BatchResponse(
      @JsonProperty("applied") int applied,
      @JsonProperty("firstOrdinal") long firstOrdinal,
      @JsonProperty("lastOrdinal") long lastOrdinal,
      @JsonProperty("resoutBase64") String resoutBase64) {}

  public record PublishRequest(
      @JsonProperty("fence") long fence,
      @JsonProperty("mode") String mode,
      @JsonProperty("manifestBase64") String manifestBase64) {}

  public record FenceRequest(@JsonProperty("fence") long fence) {}

  public record CurrentResponse(
      @JsonProperty("namespace") String namespace, @JsonProperty("current") String current) {}

  public record PolicyResponse(
      @JsonProperty("namespace") String namespace,
      @JsonProperty("generation") String generation,
      @JsonProperty("id") String id,
      @JsonProperty("recordHex") String recordHex,
      @JsonProperty("issue") int issue,
      @JsonProperty("date") int date,
      @JsonProperty("seq") int seq,
      @JsonProperty("face") long face,
      @JsonProperty("cash") long cash,
      @JsonProperty("loan") long loan,
      @JsonProperty("lastRequestHex") String lastRequestHex) {}

  public record EvaluateRequest(
      @JsonProperty("masterHex") String masterHex, @JsonProperty("recordHex") String recordHex) {}

  public record EvaluateResponse(
      @JsonProperty("status") String status,
      @JsonProperty("accepted") boolean accepted,
      @JsonProperty("resultHex") String resultHex,
      @JsonProperty("masterHex") String masterHex,
      @JsonProperty("result") TypedResult result) {}

  public record ErrorResponse(
      @JsonProperty("kind") String kind, @JsonProperty("message") String message) {}
}
