package insurance.ledger.typed;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Typed (JSON) view of a 40-byte request. It exists only for requests whose bytes are exactly
 * reproducible from these five fields; everything else must travel as raw bytes.
 */
public record TypedTransaction(
    @JsonProperty("id") String id,
    @JsonProperty("seq") int seq,
    @JsonProperty("date") String date,
    @JsonProperty("op") String op,
    @JsonProperty("amount") long amount) {}
