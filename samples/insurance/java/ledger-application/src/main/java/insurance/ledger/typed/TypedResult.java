package insurance.ledger.typed;

import com.fasterxml.jackson.annotation.JsonProperty;
import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.Layout.Result;
import insurance.legacy.codec.ResultRecord;

/**
 * Typed view of a 96-byte result. Every field is decoded from the record bytes; {@code recordHex}
 * carries the same bytes so a caller (and the parity driver) can check the fields independently.
 */
public record TypedResult(
    @JsonProperty("id") String id,
    @JsonProperty("seq") int seq,
    @JsonProperty("date") String date,
    @JsonProperty("dateYmd") int dateYmd,
    @JsonProperty("status") String status,
    @JsonProperty("op") String op,
    @JsonProperty("age") int age,
    @JsonProperty("rate") long rate,
    @JsonProperty("fee") long fee,
    @JsonProperty("cash") long cash,
    @JsonProperty("surrender") long surrender,
    @JsonProperty("death") long death,
    @JsonProperty("loan") long loan,
    @JsonProperty("interest") long interest,
    @JsonProperty("charge") long charge,
    @JsonProperty("version") String version,
    @JsonProperty("recordHex") String recordHex) {

  public static TypedResult of(ResultRecord r) {
    byte[] raw = r.bytes();
    return new TypedResult(
        Cp037.roundTrips(raw, Result.ID, Result.ID_LENGTH)
            ? Cp037.decode(raw, Result.ID, Result.ID_LENGTH)
            : null,
        r.seq(),
        TypedCodec.isoDate(r.date()).orElse(null),
        r.date(),
        r.status(),
        Cp037.decode(raw, Result.OP, 1),
        r.age(),
        r.rate(),
        r.fee(),
        r.cash(),
        r.surrender(),
        r.death(),
        r.loan(),
        r.interest(),
        r.charge(),
        r.version(),
        Hex.of(raw));
  }
}
