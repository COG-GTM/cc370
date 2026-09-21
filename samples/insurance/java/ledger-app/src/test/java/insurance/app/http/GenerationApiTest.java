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
import insurance.app.http.Api.ClaimRequest;
import insurance.app.http.Api.ClaimResponse;
import insurance.app.http.Api.DiscardAbandonedRequest;
import insurance.app.http.Api.EvaluateRequest;
import insurance.app.http.Api.EvaluateResponse;
import insurance.app.http.Api.FenceRequest;
import insurance.app.http.Api.ImportRequest;
import insurance.app.http.Api.LeaseResponse;
import insurance.app.http.Api.PolicyResponse;
import insurance.app.http.Api.PublishRequest;
import insurance.app.http.Api.RawRequest;
import insurance.app.http.Api.TypedRequest;
import insurance.contract.v001.ContractV001;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.Json;
import insurance.ledger.PolicyLedgerService;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.ledger.batch.BatchRunner;
import insurance.ledger.typed.TypedCodec;
import insurance.ledger.typed.TypedTransaction;
import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Fullword;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.Layout.Transaction;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

  /**
   * Generations that are never published pin a placeholder request stream (three zero records): the
   * manifest must exist and bind to the seed and the rate table before any request is taken.
   */
  private LeaseResponse importAndBegin(String gen) {
    return importAndBegin(gen, manifest("a", 2, 3, seed(), new byte[120]));
  }

  private LeaseResponse importAndBegin(String gen, ExpectedManifest pinned) {
    ResponseEntity<String> imported =
        http.postForEntity(
            NS + "/import",
            new ImportRequest(
                "root", b64(seed()), manifestB64(manifest("bootstrap", 2, 0, seed(), new byte[0]))),
            String.class);
    assertEquals(HttpStatus.CREATED, imported.getStatusCode(), imported.getBody());
    ResponseEntity<String> begun =
        http.postForEntity(
            NS + "/generations",
            new BeginRequest("root", gen, b64(seed()), manifestB64(pinned)),
            String.class);
    assertEquals(HttpStatus.CREATED, begun.getStatusCode(), begun.getBody());
    return Json.read(begun.getBody().getBytes(StandardCharsets.UTF_8), LeaseResponse.class);
  }

  private ResponseEntity<String> publish(LeaseResponse lease, String mode) {
    return http.postForEntity(
        NS + "/generations/" + lease.generation() + "/publish",
        new PublishRequest(lease.fence(), mode),
        String.class);
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
    return Json.read(r.getBody().getBytes(StandardCharsets.UTF_8), ApplyResponse.class);
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
    TransactionRecord t1 = txn("00000001", 1, VALUATION, 'P', 10_000);
    TransactionRecord t2 = txn("00000002", 1, VALUATION, 'W', 5_000);
    byte[] txnin = concat(t1.bytes(), t2.bytes());
    LeaseResponse lease = importAndBegin("g1", manifest("a", 2, 2, seed(), txnin));
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
    ResponseEntity<String> published = publish(lease, "http-json");
    assertEquals(HttpStatus.OK, published.getStatusCode(), published.getBody());
    Receipt receipt =
        Json.read(published.getBody().getBytes(StandardCharsets.UTF_8), Receipt.class);
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
    TransactionRecord t1 = txn("00000001", 1, VALUATION, 'P', 10_000);
    LeaseResponse lease = importAndBegin("slow", manifest("a", 2, 1, seed(), t1.bytes()));
    assertEquals(
        HttpStatus.CONFLICT,
        http.postForEntity(
                NS + "/generations/slow/requests:raw",
                new RawRequest(lease.fence() + 7, Hex.of(t1.bytes())),
                String.class)
            .getStatusCode());
    // another writer publishes first
    ResponseEntity<String> fastBegun =
        http.postForEntity(
            NS + "/generations",
            new BeginRequest(
                "root", "fast", null, manifestB64(manifest("a", 2, 1, seed(), t1.bytes()))),
            String.class);
    assertEquals(HttpStatus.CREATED, fastBegun.getStatusCode(), fastBegun.getBody());
    LeaseResponse fast =
        Json.read(fastBegun.getBody().getBytes(StandardCharsets.UTF_8), LeaseResponse.class);
    apply(raw(fast, t1.bytes()));
    assertEquals(HttpStatus.OK, publish(fast, "http-gen").getStatusCode());
    apply(raw(lease, t1.bytes()));
    ResponseEntity<String> cas = publish(lease, "http-gen");
    assertEquals(HttpStatus.CONFLICT, cas.getStatusCode());
    assertTrue(cas.getBody().contains("\"kind\":\"cas\""), cas.getBody());
    assertEquals(
        "DISCARDED",
        http.getForObject(NS + "/generations/slow", JsonNode.class).get("status").asText());
    assertEquals(
        "fast", http.getForObject(NS + "/current", JsonNode.class).get("current").asText());
  }

  // ---------------------------------------------------------------- claim / resume

  private void expireLease(String gen) {
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE generation SET lease_expires_at = now() - interval '1 second'"
                + " WHERE namespace = 'api' AND name = ? AND status = 'PENDING'",
            gen);
  }

  private ResponseEntity<String> claim(String gen, ExpectedManifest manifest) {
    return http.postForEntity(
        NS + "/generations/" + gen + "/claim",
        new ClaimRequest(manifestB64(manifest)),
        String.class);
  }

  @Test
  void claimRouteTakesOverAnExpiredWriterVerifiesThePrefixAndResumesAtTheNextOrdinal() {
    List<TransactionRecord> reqs =
        List.of(
            txn("00000001", 1, VALUATION, 'P', 10_000),
            txn("00000001", 1, VALUATION, 'P', 10_000),
            txn("00000002", 1, VALUATION, 'W', 5_000));
    ExpectedManifest pinned = manifest("a", 2, 3, seed(), Records.join(reqs));
    LeaseResponse old = importAndBegin("g1", pinned);
    apply(raw(old, reqs.get(0).bytes()));
    apply(raw(old, reqs.get(1).bytes()));
    byte[] prefix = Records.join(reqs.subList(0, 2));

    // the instance that holds the live lease is not displaced: a claim addressed to it is
    // reconciliation and hands back the fence it already holds (no transition, no claim row)
    ResponseEntity<String> live = claim("g1", pinned);
    assertEquals(HttpStatus.OK, live.getStatusCode(), live.getBody());
    ClaimResponse held =
        Json.read(live.getBody().getBytes(StandardCharsets.UTF_8), ClaimResponse.class);
    assertEquals(old.fence(), held.fence());
    assertEquals(0, held.claims());
    assertEquals(2, held.lastOrdinal());
    assertEquals(Sha256.of(prefix), held.committedRequestsSha256());
    // (a live lease held by ANOTHER writer is refused: JdbcTakeoverTest, ServiceKillTest)

    expireLease("g1");
    // the expired writer cannot commit or publish any more
    assertEquals(HttpStatus.CONFLICT, raw(old, reqs.get(2).bytes()).getStatusCode());
    assertEquals(HttpStatus.CONFLICT, publish(old, "http-gen").getStatusCode());
    // a claim under another manifest is a checkpoint conflict and changes nothing
    ResponseEntity<String> wrong = claim("g1", manifest("a", 2, 2, seed(), prefix));
    assertEquals(HttpStatus.CONFLICT, wrong.getStatusCode(), wrong.getBody());
    assertTrue(wrong.getBody().contains("\"kind\":\"checkpoint\""), wrong.getBody());
    JsonNode still = http.getForObject(NS + "/generations/g1", JsonNode.class);
    assertEquals("PENDING", still.get("status").asText());
    assertEquals(2, still.get("last_ordinal").asInt());

    ResponseEntity<String> claimed = claim("g1", pinned);
    assertEquals(HttpStatus.OK, claimed.getStatusCode(), claimed.getBody());
    ClaimResponse c =
        Json.read(claimed.getBody().getBytes(StandardCharsets.UTF_8), ClaimResponse.class);
    assertEquals(2, c.lastOrdinal());
    assertEquals(1, c.claims());
    assertTrue(c.fence() > old.fence());
    assertEquals(Sha256.of(prefix), c.committedRequestsSha256());
    // the claim's response was lost: the same replacement writer asks again and gets the
    // same fence, claim number and prefix back without another ownership transition
    ResponseEntity<String> lost = claim("g1", pinned);
    assertEquals(HttpStatus.OK, lost.getStatusCode(), lost.getBody());
    assertEquals(
        c, Json.read(lost.getBody().getBytes(StandardCharsets.UTF_8), ClaimResponse.class));
    byte[] committed =
        http.getForEntity(NS + "/generations/g1/peek/requests", byte[].class).getBody();
    assertArrayEquals(prefix, committed);
    assertEquals(2, PolicyLedgerService.resumeIndex(committed, reqs));

    LeaseResponse mine = new LeaseResponse("api", "g1", "root", c.fence());
    ApplyResponse third = apply(raw(mine, reqs.get(2).bytes()));
    assertEquals(3, third.ordinal());
    assertEquals(HttpStatus.CONFLICT, raw(old, reqs.get(2).bytes()).getStatusCode());
    ResponseEntity<String> published = publish(mine, "http-gen");
    assertEquals(HttpStatus.OK, published.getStatusCode(), published.getBody());
    BatchRunner.Output oracle =
        new BatchRunner(ContractV001.frozen()).run(seed(), Records.join(reqs));
    assertArrayEquals(
        oracle.resout(), http.getForEntity(NS + "/generations/g1/resout", byte[].class).getBody());
    assertArrayEquals(
        oracle.polout(), http.getForEntity(NS + "/generations/g1/polout", byte[].class).getBody());
    // published: neither claim nor abandoned discard applies any more
    ResponseEntity<String> late = claim("g1", pinned);
    assertEquals(HttpStatus.CONFLICT, late.getStatusCode());
    assertTrue(late.getBody().contains("\"kind\":\"lifecycle\""), late.getBody());
  }

  @Test
  void discardAbandonedRouteIsExplicitAndRequiresAnExpiredLease() {
    TransactionRecord t1 = txn("00000001", 1, VALUATION, 'P', 10_000);
    LeaseResponse lease = importAndBegin("g1", manifest("a", 2, 1, seed(), t1.bytes()));
    apply(raw(lease, t1.bytes()));
    ResponseEntity<String> live =
        http.postForEntity(
            NS + "/generations/g1/discard-abandoned",
            new DiscardAbandonedRequest("too early"),
            String.class);
    assertEquals(HttpStatus.CONFLICT, live.getStatusCode(), live.getBody());
    expireLease("g1");
    ResponseEntity<String> discarded =
        http.postForEntity(
            NS + "/generations/g1/discard-abandoned",
            new DiscardAbandonedRequest("operator"),
            String.class);
    assertEquals(HttpStatus.OK, discarded.getStatusCode(), discarded.getBody());
    assertTrue(discarded.getBody().contains("DISCARDED"), discarded.getBody());
    assertEquals(
        HttpStatus.CONFLICT, claim("g1", manifest("a", 2, 1, seed(), t1.bytes())).getStatusCode());
    assertEquals(
        "root", http.getForObject(NS + "/current", JsonNode.class).get("current").asText());
  }

  @Test
  void manifestPinnedAtCreationIsEnforcedAtPublishWith422AndDiscards() {
    TransactionRecord t1 = txn("00000001", 1, VALUATION, 'P', 10_000);
    // pinned: two requests expected, only one arrives
    LeaseResponse lease = importAndBegin("g1", manifest("a", 2, 2, seed(), t1.bytes()));
    apply(raw(lease, t1.bytes()));
    ResponseEntity<String> r = publish(lease, "http-gen");
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r.getStatusCode(), r.getBody());
    assertEquals(
        "DISCARDED",
        http.getForObject(NS + "/generations/g1", JsonNode.class).get("status").asText());
    // the pinned manifest is readable for the generation and publish takes no manifest at all
    JsonNode pinned = http.getForObject(NS + "/generations/g1/manifest", JsonNode.class);
    assertEquals(2, pinned.get("transactions_count").asInt());
    assertEquals(ExpectedManifest.SCHEMA, pinned.get("schema").asText());
  }

  @Test
  void generationCreationRejectsMissingUnsupportedOrMisboundManifests() {
    importAndBegin("ok");
    // missing manifest
    ResponseEntity<String> none =
        http.postForEntity(
            NS + "/generations",
            new BeginRequest("root", "g-none", b64(seed()), null),
            String.class);
    assertEquals(HttpStatus.BAD_REQUEST, none.getStatusCode(), none.getBody());
    // unsupported schema
    ExpectedManifest good = manifest("a", 2, 1, seed(), new byte[40]);
    ExpectedManifest badSchema =
        new ExpectedManifest(
            "insurance-expected-manifest-v0",
            good.stage(),
            good.policiesCount(),
            good.transactionsCount(),
            good.polinSha256(),
            good.txninSha256(),
            good.ratesSha256(),
            null);
    ResponseEntity<String> schema =
        http.postForEntity(
            NS + "/generations",
            new BeginRequest("root", "g-schema", b64(seed()), manifestB64(badSchema)),
            String.class);
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, schema.getStatusCode(), schema.getBody());
    // rate table not the running V001 table
    ExpectedManifest badRates =
        new ExpectedManifest(
            good.schema(),
            good.stage(),
            good.policiesCount(),
            good.transactionsCount(),
            good.polinSha256(),
            good.txninSha256(),
            "0".repeat(64),
            null);
    ResponseEntity<String> rates =
        http.postForEntity(
            NS + "/generations",
            new BeginRequest("root", "g-rates", b64(seed()), manifestB64(badRates)),
            String.class);
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rates.getStatusCode(), rates.getBody());
    // seed hash / count disagree with the published parent
    ResponseEntity<String> seedHash =
        http.postForEntity(
            NS + "/generations",
            new BeginRequest(
                "root",
                "g-seed",
                b64(seed()),
                manifestB64(manifest("a", 2, 1, a001().bytes(), new byte[40]))),
            String.class);
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, seedHash.getStatusCode(), seedHash.getBody());
    ResponseEntity<String> count =
        http.postForEntity(
            NS + "/generations",
            new BeginRequest(
                "root",
                "g-count",
                b64(seed()),
                manifestB64(manifest("a", 1, 1, seed(), new byte[40]))),
            String.class);
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, count.getStatusCode(), count.getBody());
    // supplied POLIN differs from the pinned parent bytes
    ResponseEntity<String> polin =
        http.postForEntity(
            NS + "/generations",
            new BeginRequest("root", "g-polin", b64(a001().bytes()), manifestB64(good)),
            String.class);
    assertEquals(HttpStatus.CONFLICT, polin.getStatusCode(), polin.getBody());
    // none of the rejected generations exist
    for (String g : List.of("g-none", "g-schema", "g-rates", "g-seed", "g-count", "g-polin")) {
      assertEquals(
          HttpStatus.NOT_FOUND,
          http.getForEntity(NS + "/generations/" + g, String.class).getStatusCode(),
          g);
    }
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

  // ---------------------------------------------------------------- T-11 admission order

  /**
   * 200 concurrent HTTP requests against one pending generation. The admission ticket ({@link
   * AdmissionSequencer#HEADER}) is assigned when the servlet chain first sees the request; the
   * committed ordinal must equal that ticket for every request, and the persisted request and
   * result streams must be in ticket order. Each client thread records its own (ticket, request,
   * result) triple, so the physical order is checked against evidence gathered outside the store.
   */
  @Test
  void twoHundredConcurrentRequestsCommitInAdmissionOrder() throws Exception {
    int n = 200;
    byte[][] masters = new byte[n][];
    for (int i = 0; i < n; i++) {
      masters[i] = fresh(String.format("%08d", i + 1), 500_000, 20_000 + i, 0).bytes();
    }
    byte[] seed = concat(masters);
    ResponseEntity<String> imported =
        http.postForEntity(
            NS + "/import",
            new ImportRequest(
                "root", b64(seed), manifestB64(manifest("bootstrap", n, 0, seed, new byte[0]))),
            String.class);
    assertEquals(HttpStatus.CREATED, imported.getStatusCode(), imported.getBody());
    ResponseEntity<String> begun =
        http.postForEntity(
            NS + "/generations",
            new BeginRequest(
                "root",
                "wide",
                b64(seed),
                manifestB64(manifest("a", n, n, seed, new byte[40 * n]))),
            String.class);
    assertEquals(HttpStatus.CREATED, begun.getStatusCode(), begun.getBody());
    LeaseResponse lease =
        Json.read(begun.getBody().getBytes(StandardCharsets.UTF_8), LeaseResponse.class);

    record Observed(long ticket, byte[] request, ApplyResponse response) {}
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(32);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    List<java.util.concurrent.Future<Observed>> futures = new java.util.ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        byte[] request = txn(String.format("%08d", i + 1), 1, VALUATION, 'P', 100 + i).bytes();
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  ResponseEntity<String> r = raw(lease, request);
                  long ticket = Long.parseLong(r.getHeaders().getFirst(AdmissionSequencer.HEADER));
                  return new Observed(ticket, request, apply(r));
                }));
      }
      go.countDown();
      List<Observed> observed = new java.util.ArrayList<>();
      for (java.util.concurrent.Future<Observed> f : futures) {
        observed.add(f.get(120, java.util.concurrent.TimeUnit.SECONDS));
      }
      observed.sort(java.util.Comparator.comparingLong(Observed::ticket));
      byte[] resout =
          http.getForEntity(NS + "/generations/wide/peek/resout", byte[].class).getBody();
      assertEquals(96 * n, resout.length);
      List<byte[]> persistedRequests = new java.util.ArrayList<>();
      try (java.sql.Connection c = dataSource.getConnection();
          java.sql.PreparedStatement ps =
              c.prepareStatement(
                  "SELECT e.request FROM generation_entry e JOIN generation g ON g.id ="
                      + " e.generation_id WHERE g.namespace = 'api' AND g.name = 'wide' ORDER BY"
                      + " e.ordinal");
          java.sql.ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          persistedRequests.add(rs.getBytes(1));
        }
      }
      assertEquals(n, persistedRequests.size());
      for (int i = 0; i < n; i++) {
        Observed o = observed.get(i);
        assertEquals(i + 1, o.ticket(), "tickets are dense");
        assertEquals(o.ticket(), o.response().ordinal(), "ordinal follows admission");
        assertEquals("OKAY", o.response().status());
        assertArrayEquals(o.request(), Hex.parse(o.response().requestHex()));
        assertArrayEquals(o.request(), persistedRequests.get(i), "physical order = tickets");
        assertArrayEquals(
            Hex.parse(o.response().resultHex()),
            java.util.Arrays.copyOfRange(resout, i * 96, (i + 1) * 96));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Mixed admission: single raw requests, envelope-rejected requests and multi-record batches
   * interleaved concurrently. A ticket is consumed by every call; a rejected envelope commits no
   * ordinal, a batch commits one ordinal per record. So tickets do not equal ordinals here — what
   * holds is that committed ordinals (and the persisted request stream) follow the admission order
   * of the accepted calls, with each batch occupying a contiguous ordinal run.
   */
  @Test
  void mixedRejectedAndBatchCallsPreserveAdmissionOrderWithoutTicketOrdinalEquality()
      throws Exception {
    int singles = 40;
    int rejected = 10;
    int batches = 10;
    int perBatch = 3;
    int policies = singles + batches * perBatch;
    byte[][] masters = new byte[policies][];
    for (int i = 0; i < policies; i++) {
      masters[i] = fresh(String.format("%08d", i + 1), 500_000, 20_000 + i, 0).bytes();
    }
    byte[] seed = concat(masters);
    assertEquals(
        HttpStatus.CREATED,
        http.postForEntity(
                NS + "/import",
                new ImportRequest(
                    "root",
                    b64(seed),
                    manifestB64(manifest("bootstrap", policies, 0, seed, new byte[0]))),
                String.class)
            .getStatusCode());
    ResponseEntity<String> begun =
        http.postForEntity(
            NS + "/generations",
            new BeginRequest(
                "root",
                "mixed",
                b64(seed),
                manifestB64(manifest("a", policies, policies, seed, new byte[40 * policies]))),
            String.class);
    assertEquals(HttpStatus.CREATED, begun.getStatusCode(), begun.getBody());
    LeaseResponse lease =
        Json.read(begun.getBody().getBytes(StandardCharsets.UTF_8), LeaseResponse.class);

    // kind: 0 = accepted single, 1 = rejected envelope (39-byte record), 2 = batch of perBatch
    record Call(
        long ticket,
        int kind,
        org.springframework.http.HttpStatusCode status,
        List<byte[]> requests,
        long first,
        long last) {}
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(24);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    List<java.util.concurrent.Future<Call>> futures = new java.util.ArrayList<>();
    try {
      int policy = 1;
      int[] pattern = {0, 0, 2, 0, 0, 1}; // 4 singles, 1 batch, 1 rejection per 6 calls
      for (int i = 0; i < singles + rejected + batches; i++) {
        final int k = pattern[i % pattern.length];
        final List<byte[]> requests = new java.util.ArrayList<>();
        if (k == 0) {
          requests.add(txn(String.format("%08d", policy++), 1, VALUATION, 'P', 100 + i).bytes());
        } else if (k == 2) {
          for (int j = 0; j < perBatch; j++) {
            requests.add(txn(String.format("%08d", policy++), 1, VALUATION, 'P', 100 + i).bytes());
          }
        }
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  if (k == 1) {
                    ResponseEntity<String> r =
                        http.postForEntity(
                            NS + "/generations/mixed/requests:raw",
                            new RawRequest(lease.fence(), Hex.of(new byte[39])),
                            String.class);
                    long ticket =
                        Long.parseLong(r.getHeaders().getFirst(AdmissionSequencer.HEADER));
                    return new Call(ticket, k, r.getStatusCode(), requests, 0, 0);
                  }
                  if (k == 2) {
                    ResponseEntity<String> r =
                        http.postForEntity(
                            NS + "/generations/mixed/batch",
                            new BatchRequest(
                                lease.fence(),
                                b64(
                                    Records.join(
                                        requests.stream().map(TransactionRecord::new).toList()))),
                            String.class);
                    long ticket =
                        Long.parseLong(r.getHeaders().getFirst(AdmissionSequencer.HEADER));
                    BatchResponse b =
                        Json.read(
                            r.getBody().getBytes(StandardCharsets.UTF_8), BatchResponse.class);
                    return new Call(
                        ticket, k, r.getStatusCode(), requests, b.firstOrdinal(), b.lastOrdinal());
                  }
                  ResponseEntity<String> r = raw(lease, requests.get(0));
                  long ticket = Long.parseLong(r.getHeaders().getFirst(AdmissionSequencer.HEADER));
                  ApplyResponse a = apply(r);
                  return new Call(ticket, k, r.getStatusCode(), requests, a.ordinal(), a.ordinal());
                }));
      }
      go.countDown();
      List<Call> calls = new java.util.ArrayList<>();
      for (java.util.concurrent.Future<Call> f : futures) {
        calls.add(f.get(120, java.util.concurrent.TimeUnit.SECONDS));
      }
      calls.sort(java.util.Comparator.comparingLong(Call::ticket));

      List<byte[]> persisted = new java.util.ArrayList<>();
      try (java.sql.Connection c = dataSource.getConnection();
          java.sql.PreparedStatement ps =
              c.prepareStatement(
                  "SELECT e.request FROM generation_entry e JOIN generation g ON g.id ="
                      + " e.generation_id WHERE g.namespace = 'api' AND g.name = 'mixed' ORDER BY"
                      + " e.ordinal");
          java.sql.ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          persisted.add(rs.getBytes(1));
        }
      }

      long nextOrdinal = 1;
      int rejectedSeen = 0;
      int batchesSeen = 0;
      boolean ticketDiffersFromOrdinal = false;
      for (int i = 0; i < calls.size(); i++) {
        Call call = calls.get(i);
        assertEquals(i + 1, call.ticket(), "every call consumes exactly one ticket");
        if (call.kind() == 1) {
          assertEquals(HttpStatus.BAD_REQUEST, call.status(), "envelope rejection");
          rejectedSeen++;
          continue; // no ordinal
        }
        assertEquals(HttpStatus.OK, call.status());
        assertEquals(
            nextOrdinal, call.first(), "ordinals follow the admission order of accepted calls");
        assertEquals(
            nextOrdinal + call.requests().size() - 1, call.last(), "batch run is contiguous");
        for (byte[] request : call.requests()) {
          assertArrayEquals(request, persisted.get((int) nextOrdinal - 1), "physical order");
          nextOrdinal++;
        }
        ticketDiffersFromOrdinal |= call.ticket() != call.first();
        if (call.kind() == 2) {
          batchesSeen++;
        }
      }
      assertEquals(10, rejectedSeen);
      assertEquals(10, batchesSeen);
      assertEquals(persisted.size(), nextOrdinal - 1);
      assertEquals(40 + 10 * perBatch, persisted.size());
      assertEquals(
          persisted.size(),
          http.getForObject(NS + "/generations/mixed", JsonNode.class).get("last_ordinal").asInt());
      assertTrue(
          ticketDiffersFromOrdinal, "tickets are not ordinals once a call is rejected or batched");
    } finally {
      pool.shutdownNow();
    }
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
