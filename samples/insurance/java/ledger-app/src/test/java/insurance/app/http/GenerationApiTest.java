package insurance.app.http;

import static insurance.app.PostgresSupport.VALUATION;
import static insurance.app.PostgresSupport.a001;
import static insurance.app.PostgresSupport.concat;
import static insurance.app.PostgresSupport.fresh;
import static insurance.app.PostgresSupport.manifest;
import static insurance.app.PostgresSupport.txn;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import insurance.app.PostgresSupport;
import insurance.app.http.Api.ApplyResponse;
import insurance.app.http.Api.BatchRequest;
import insurance.app.http.Api.BatchResponse;
import insurance.app.http.Api.BeginRequest;
import insurance.app.http.Api.EvaluateRequest;
import insurance.app.http.Api.EvaluateResponse;
import insurance.app.http.Api.FenceRequest;
import insurance.app.http.Api.ImportRequest;
import insurance.app.http.Api.LeaseResponse;
import insurance.app.http.Api.PolicyResponse;
import insurance.app.http.Api.PublishRequest;
import insurance.app.http.Api.RawRequest;
import insurance.app.http.Api.TypedRequest;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.Json;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.ledger.typed.TypedCodec;
import insurance.ledger.typed.TypedTransaction;
import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Fullword;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.Layout.Transaction;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenerationApiTest {
  private static final String NS = "/v1/namespaces/api";

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    PostgresSupport.register(registry);
  }

  @Autowired TestRestTemplate http;
  @Autowired DataSource dataSource;

  @BeforeEach
  void reset() {
    PostgresSupport.resetSchema(dataSource);
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static String manifestB64(ExpectedManifest m) {
    return b64(Json.bytes(m));
  }

  private static byte[] seed() {
    return concat(a001().bytes(), fresh("00000002", 500_000, 20_000, 0).bytes());
  }

  private LeaseResponse importAndBegin(String gen) {
    ResponseEntity<String> imported =
        http.postForEntity(
            NS + "/import",
            new ImportRequest(
                "root", b64(seed()), manifestB64(manifest("bootstrap", 2, 0, seed(), new byte[0]))),
            String.class);
    assertEquals(HttpStatus.CREATED, imported.getStatusCode(), imported.getBody());
    ResponseEntity<LeaseResponse> begun =
        http.postForEntity(
            NS + "/generations", new BeginRequest("root", gen, b64(seed())), LeaseResponse.class);
    assertEquals(HttpStatus.CREATED, begun.getStatusCode());
    return begun.getBody();
  }

  private ResponseEntity<String> typed(LeaseResponse lease, TypedTransaction t) {
    return http.postForEntity(
        NS + "/generations/" + lease.generation() + "/requests",
        new TypedRequest(lease.fence(), t),
        String.class);
  }

  private ResponseEntity<String> raw(LeaseResponse lease, byte[] record) {
    return http.postForEntity(
        NS + "/generations/" + lease.generation() + "/requests:raw",
        new RawRequest(lease.fence(), Hex.of(record)),
        String.class);
  }

  private static ApplyResponse apply(ResponseEntity<String> r) {
    assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
    return Json.read(
        r.getBody().getBytes(java.nio.charset.StandardCharsets.UTF_8), ApplyResponse.class);
  }

  /** Every typed response field must agree with the independently decoded result bytes. */
  private static void assertTypedFieldsMatchBytes(ApplyResponse a) {
    ResultRecord r = new ResultRecord(Hex.parse(a.resultHex()));
    assertEquals(a.status(), r.status());
    assertNotNull(a.result());
    assertEquals(Cp037.decode(r.bytes(), 0, 8), a.result().id());
    assertEquals(r.seq(), a.result().seq());
    assertEquals(r.date(), a.result().dateYmd());
    assertEquals(r.status(), a.result().status());
    assertEquals(r.age(), a.result().age());
    assertEquals(r.rate(), a.result().rate());
    assertEquals(r.fee(), a.result().fee());
    assertEquals(r.cash(), a.result().cash());
    assertEquals(r.surrender(), a.result().surrender());
    assertEquals(r.death(), a.result().death());
    assertEquals(r.loan(), a.result().loan());
    assertEquals(r.interest(), a.result().interest());
    assertEquals(r.charge(), a.result().charge());
    assertEquals("V001", a.result().version());
    assertEquals(a.resultHex(), a.result().recordHex());
  }

  // ---------------------------------------------------------------- typed vs raw

  @Test
  void typedRequestReachesTheContractAndFieldsMatchResultBytes() {
    LeaseResponse lease = importAndBegin("g1");
    ApplyResponse a =
        apply(typed(lease, new TypedTransaction("00000001", 1, "2025-01-01", "P", 10_000)));
    assertTrue(a.typed());
    assertEquals("OKAY", a.status());
    assertTrue(a.accepted());
    assertEquals(1, a.ordinal());
    assertTypedFieldsMatchBytes(a);
    assertEquals(3_259, a.result().interest());
    assertEquals(113_259, a.result().cash());
    assertEquals(3_964, a.result().charge());
    assertEquals(109_295, a.result().surrender());
    assertEquals(1_000_000, a.result().death());
    assertEquals(1, a.result().age());
    // the bytes that were committed are exactly what the raw encoder would have produced
    assertEquals(Hex.of(txn("00000001", 1, VALUATION, 'P', 10_000).bytes()), a.requestHex());
  }

  @Test
  void representableDomainFailuresAreHttp200WithLegacyStatus() {
    LeaseResponse lease = importAndBegin("g1");
    ApplyResponse over =
        apply(
            typed(
                lease, new TypedTransaction("00000001", 1, "2025-01-01", "P", 1_000_000_000_000L)));
    assertEquals("OVER", over.status());
    assertFalse(over.accepted());
    assertTypedFieldsMatchBytes(over);
    ApplyResponse date =
        apply(typed(lease, new TypedTransaction("00000001", 1, "2100-01-01", "P", 1)));
    assertEquals("DATE", date.status());
    assertTypedFieldsMatchBytes(date);
    ApplyResponse npol =
        apply(typed(lease, new TypedTransaction("00000009", 1, "2025-01-01", "P", 1)));
    assertEquals("NPOL", npol.status());
    assertTypedFieldsMatchBytes(npol);
    // rejections are persisted with ordinals but leave the master untouched
    assertEquals(3, npol.ordinal());
  }

  @Test
  void envelopeFailuresAreHttp400AndConsumeNoOrdinal() {
    LeaseResponse lease = importAndBegin("g1");
    assertEquals(
        HttpStatus.BAD_REQUEST,
        typed(lease, new TypedTransaction("00000001", 1, "2025-01-01", "P", 10_000_000_000_000L))
            .getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        typed(lease, new TypedTransaction("00000001", 1, "99999-01-01", "P", 1)).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        typed(lease, new TypedTransaction("0000001", 1, "2025-01-01", "P", 1)).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        typed(lease, new TypedTransaction("00000001", 1, "2025-1-1", "P", 1)).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        typed(lease, new TypedTransaction("00000001", 1, "2025-01-01", "PP", 1)).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        typed(lease, new TypedTransaction("00000001", 1, "2025-01-01", "P", Long.MIN_VALUE))
            .getStatusCode());
    assertEquals(HttpStatus.BAD_REQUEST, raw(lease, new byte[39]).getStatusCode());
    HttpHeaders json = new HttpHeaders();
    json.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> unknownField =
        http.postForEntity(
            NS + "/generations/g1/requests",
            new HttpEntity<>(
                "{\"fence\":" + lease.fence() + ",\"request\":{\"id\":\"00000001\",\"bogus\":1}}",
                json),
            String.class);
    assertEquals(HttpStatus.BAD_REQUEST, unknownField.getStatusCode());
    ApplyResponse next =
        apply(typed(lease, new TypedTransaction("00000001", 1, "2025-01-01", "P", 1)));
    assertEquals(1, next.ordinal());
  }

  @Test
  void byteNonRepresentableRequestsGoRawAndAreReportedAsRaw() {
    LeaseResponse lease = importAndBegin("g1");
    // out-of-grammar date 99999-01-01 is representable as the fullword 999990101 -> domain DATE
    byte[] wideDate = txn("00000001", 1, 999990101, 'P', 1).bytes();
    ApplyResponse a = apply(raw(lease, wideDate));
    assertFalse(a.typed());
    assertEquals("DATE", a.status());
    assertTypedFieldsMatchBytes(a);
    assertTrue(TypedCodec.decode(new TransactionRecord(wideDate)).isEmpty());
    // F sign: numerically identical, byte-different, accepted by the contract
    byte[] fSign = txn("00000001", 1, VALUATION, 'P', 10_000).bytes();
    fSign[Transaction.AMOUNT + Transaction.AMOUNT_LENGTH - 1] =
        (byte) ((fSign[Transaction.AMOUNT + Transaction.AMOUNT_LENGTH - 1] & 0xF0) | 0x0F);
    assertTrue(TypedCodec.decode(new TransactionRecord(fSign)).isEmpty());
    ApplyResponse f = apply(raw(lease, fSign));
    assertEquals("OKAY", f.status());
    assertEquals(Hex.of(fSign), f.requestHex());
    // the raw bytes, not a normalised copy, are what SLAST now holds
    ResponseEntity<byte[]> peek =
        http.getForEntity(NS + "/generations/g1/peek/polout", byte[].class);
    assertEquals(HttpStatus.OK, peek.getStatusCode());
    assertArrayEquals(fSign, Records.policies(peek.getBody()).get(0).last());
    // nonzero tail -> FORM; malformed packed -> PACK; both raw only
    byte[] tail = txn("00000001", 2, VALUATION, 'P', 1).bytes();
    tail[39] = 0x01;
    assertEquals("FORM", apply(raw(lease, tail)).status());
    byte[] pack = txn("00000001", 2, VALUATION, 'P', 1).bytes();
    pack[Transaction.AMOUNT] = (byte) 0xAA;
    assertEquals("PACK", apply(raw(lease, pack)).status());
    // typed request with a non-CP037-round-tripping byte is impossible; raw carries it -> NPOL
    byte[] weirdId = txn("00000001", 2, VALUATION, 'P', 1).bytes();
    weirdId[0] = (byte) 0xFF;
    assertEquals("NPOL", apply(raw(lease, weirdId)).status());
  }

  // ---------------------------------------------------------------- lifecycle over HTTP

  @Test
  void publishServesOutputsOnlyAfterPublicationAndReceiptCountsTypedVersusRaw() {
    LeaseResponse lease = importAndBegin("g1");
    TransactionRecord t1 = txn("00000001", 1, VALUATION, 'P', 10_000);
    TransactionRecord t2 = txn("00000002", 1, VALUATION, 'W', 5_000);
    apply(typed(lease, TypedCodec.decode(t1).orElseThrow()));
    apply(raw(lease, t2.bytes()));
    // pending: named reads are refused, peek works
    assertEquals(
        HttpStatus.CONFLICT,
        http.getForEntity(NS + "/generations/g1/resout", byte[].class).getStatusCode());
    assertEquals(
        HttpStatus.NOT_FOUND,
        http.getForEntity(NS + "/generations/g1/receipt", String.class).getStatusCode());
    assertEquals(
        192, http.getForEntity(NS + "/generations/g1/peek/resout", byte[].class).getBody().length);
    byte[] txnin = concat(t1.bytes(), t2.bytes());
    ResponseEntity<Receipt> published =
        http.postForEntity(
            NS + "/generations/g1/publish",
            new PublishRequest(
                lease.fence(), "http-json", manifestB64(manifest("a", 2, 2, seed(), txnin))),
            Receipt.class);
    assertEquals(HttpStatus.OK, published.getStatusCode());
    Receipt receipt = published.getBody();
    assertEquals(1, receipt.typedRequests());
    assertEquals(1, receipt.rawRequests());
    assertEquals(2, receipt.resultsCount());
    assertEquals("PUBLISHED", receipt.publicationStatus());
    byte[] resout = http.getForEntity(NS + "/generations/g1/resout", byte[].class).getBody();
    byte[] polout = http.getForEntity(NS + "/generations/g1/polout", byte[].class).getBody();
    assertEquals(Sha256.of(resout), receipt.resoutSha256());
    assertEquals(Sha256.of(polout), receipt.poloutSha256());
    assertArrayEquals(
        txnin, http.getForEntity(NS + "/generations/g1/requests", byte[].class).getBody());
    JsonNode current = http.getForObject(NS + "/current", JsonNode.class);
    assertEquals("g1", current.get("current").asText());
    // published lease is dead
    assertEquals(HttpStatus.CONFLICT, raw(lease, t1.bytes()).getStatusCode());
    assertEquals(
        HttpStatus.CONFLICT,
        http.postForEntity(
                NS + "/generations/g1/discard", new FenceRequest(lease.fence()), String.class)
            .getStatusCode());
    // policy reads come from the published state
    PolicyResponse p = http.getForObject(NS + "/policies/00000001", PolicyResponse.class);
    assertEquals(113_259, p.cash());
    assertEquals(1, p.seq());
    assertEquals(Hex.of(t1.bytes()), p.lastRequestHex());
    assertEquals(
        HttpStatus.NOT_FOUND,
        http.getForEntity(NS + "/policies/00000009", String.class).getStatusCode());
  }

  @Test
  void wrongFenceAndCasFailuresAreConflicts() {
    LeaseResponse lease = importAndBegin("slow");
    TransactionRecord t1 = txn("00000001", 1, VALUATION, 'P', 10_000);
    assertEquals(
        HttpStatus.CONFLICT,
        http.postForEntity(
                NS + "/generations/slow/requests:raw",
                new RawRequest(lease.fence() + 7, Hex.of(t1.bytes())),
                String.class)
            .getStatusCode());
    // another writer publishes first
    LeaseResponse fast =
        http.postForEntity(
                NS + "/generations", new BeginRequest("root", "fast", null), LeaseResponse.class)
            .getBody();
    apply(raw(fast, t1.bytes()));
    assertEquals(
        HttpStatus.OK,
        http.postForEntity(
                NS + "/generations/fast/publish",
                new PublishRequest(
                    fast.fence(), "http-gen", manifestB64(manifest("a", 2, 1, seed(), t1.bytes()))),
                String.class)
            .getStatusCode());
    apply(raw(lease, t1.bytes()));
    ResponseEntity<String> cas =
        http.postForEntity(
            NS + "/generations/slow/publish",
            new PublishRequest(
                lease.fence(), "http-gen", manifestB64(manifest("a", 2, 1, seed(), t1.bytes()))),
            String.class);
    assertEquals(HttpStatus.CONFLICT, cas.getStatusCode());
    assertTrue(cas.getBody().contains("\"kind\":\"cas\""), cas.getBody());
    assertEquals(
        "DISCARDED",
        http.getForObject(NS + "/generations/slow", JsonNode.class).get("status").asText());
    assertEquals(
        "fast", http.getForObject(NS + "/current", JsonNode.class).get("current").asText());
  }

  @Test
  void manifestMismatchAtPublishIs422AndDiscards() {
    LeaseResponse lease = importAndBegin("g1");
    TransactionRecord t1 = txn("00000001", 1, VALUATION, 'P', 10_000);
    apply(raw(lease, t1.bytes()));
    ResponseEntity<String> r =
        http.postForEntity(
            NS + "/generations/g1/publish",
            new PublishRequest(
                lease.fence(), "http-gen", manifestB64(manifest("a", 2, 2, seed(), t1.bytes()))),
            String.class);
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r.getStatusCode(), r.getBody());
    assertEquals(
        "DISCARDED",
        http.getForObject(NS + "/generations/g1", JsonNode.class).get("status").asText());
  }

  @Test
  void batchEndpointCommitsInPhysicalOrderAndMatchesRawResults() {
    LeaseResponse lease = importAndBegin("g1");
    List<TransactionRecord> txns =
        List.of(
            txn("00000001", 1, VALUATION, 'P', 10_000),
            txn("00000001", 1, VALUATION, 'P', 10_000),
            txn("00000002", 1, VALUATION, 'W', 5_000));
    byte[] txnin = Records.join(txns);
    ResponseEntity<BatchResponse> r =
        http.postForEntity(
            NS + "/generations/g1/batch",
            new BatchRequest(lease.fence(), b64(txnin)),
            BatchResponse.class);
    assertEquals(HttpStatus.OK, r.getStatusCode());
    assertEquals(3, r.getBody().applied());
    assertEquals(1, r.getBody().firstOrdinal());
    assertEquals(3, r.getBody().lastOrdinal());
    byte[] resout = Base64.getDecoder().decode(r.getBody().resoutBase64());
    assertEquals(
        List.of("OKAY", "DUPL", "OKAY"),
        Records.results(resout).stream().map(ResultRecord::status).toList());
    assertArrayEquals(
        resout, http.getForEntity(NS + "/generations/g1/peek/resout", byte[].class).getBody());
    // partial trailing record is an envelope failure and commits nothing
    byte[] partial = concat(txnin, new byte[17]);
    assertEquals(
        HttpStatus.BAD_REQUEST,
        http.postForEntity(
                NS + "/generations/g1/batch",
                new BatchRequest(lease.fence(), b64(partial)),
                String.class)
            .getStatusCode());
    assertEquals(
        3, http.getForObject(NS + "/generations/g1", JsonNode.class).get("last_ordinal").asInt());
  }

  @Test
  void importRefusesMismatchedManifestAndSecondRoot() {
    ResponseEntity<String> bad =
        http.postForEntity(
            NS + "/import",
            new ImportRequest(
                "root", b64(seed()), manifestB64(manifest("bootstrap", 1, 0, seed(), new byte[0]))),
            String.class);
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, bad.getStatusCode(), bad.getBody());
    importAndBegin("g1");
    ResponseEntity<String> again =
        http.postForEntity(
            NS + "/import",
            new ImportRequest(
                "root2",
                b64(seed()),
                manifestB64(manifest("bootstrap", 2, 0, seed(), new byte[0]))),
            String.class);
    assertEquals(HttpStatus.CONFLICT, again.getStatusCode());
    assertEquals(
        HttpStatus.NOT_FOUND,
        http.getForEntity("/v1/namespaces/nobody/current", String.class).getStatusCode());
  }

  // ---------------------------------------------------------------- stateless probe

  @Test
  void statelessEvaluateMatchesTheStatefulResultBytes() {
    TransactionRecord t1 = txn("00000001", 1, VALUATION, 'P', 10_000);
    ResponseEntity<EvaluateResponse> r =
        http.postForEntity(
            "/v1/raw/evaluate",
            new EvaluateRequest(Hex.of(a001().bytes()), Hex.of(t1.bytes())),
            EvaluateResponse.class);
    assertEquals(HttpStatus.OK, r.getStatusCode());
    EvaluateResponse e = r.getBody();
    assertEquals("OKAY", e.status());
    assertEquals(3_259, e.result().interest());
    assertEquals(e.resultHex(), e.result().recordHex());
    byte[] master = Hex.parse(e.masterHex());
    assertEquals(113_259, Records.policies(master).get(0).cash());
    assertEquals(1, Fullword.get(master, 16));
    LeaseResponse lease = importAndBegin("g1");
    ApplyResponse stateful = apply(raw(lease, t1.bytes()));
    assertEquals(e.resultHex(), stateful.resultHex());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        http.postForEntity(
                "/v1/raw/evaluate",
                new EvaluateRequest(Hex.of(new byte[127]), Hex.of(t1.bytes())),
                String.class)
            .getStatusCode());
  }
}
