package insurance.app.http;

import insurance.app.http.Api.ApplyResponse;
import insurance.app.http.Api.BatchRequest;
import insurance.app.http.Api.BatchResponse;
import insurance.app.http.Api.BeginRequest;
import insurance.app.http.Api.ClaimRequest;
import insurance.app.http.Api.ClaimResponse;
import insurance.app.http.Api.CurrentResponse;
import insurance.app.http.Api.DiscardAbandonedRequest;
import insurance.app.http.Api.FenceRequest;
import insurance.app.http.Api.ImportRequest;
import insurance.app.http.Api.LeaseResponse;
import insurance.app.http.Api.PolicyResponse;
import insurance.app.http.Api.PublishRequest;
import insurance.app.http.Api.RawRequest;
import insurance.app.http.Api.TypedRequest;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.PolicyLedgerService;
import insurance.ledger.Receipt;
import insurance.ledger.generation.GenerationInfo;
import insurance.ledger.generation.GenerationStatus;
import insurance.ledger.generation.GenerationStore;
import insurance.ledger.generation.GenerationStore.Applied;
import insurance.ledger.generation.GenerationStore.Lease;
import insurance.ledger.typed.TypedCodec;
import insurance.ledger.typed.TypedEnvelopeException;
import insurance.ledger.typed.TypedResult;
import insurance.legacy.codec.Cp037;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.TransactionRecord;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateful generation API. Every mutation is scoped to a pending generation and carries the
 * writer's fence; published outputs are served only for generations on the accepted current
 * ancestry, and pending state is reachable solely through the explicit {@code /peek} paths.
 */
@RestController
@RequestMapping(path = "/v1/namespaces/{ns}", produces = MediaType.APPLICATION_JSON_VALUE)
public class GenerationController {
  private final PolicyLedgerService service;

  public GenerationController(PolicyLedgerService service) {
    this.service = service;
  }

  private GenerationStore store() {
    return service.store();
  }

  // ------------------------------------------------------------ lifecycle

  @PostMapping("/import")
  @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
  public GenerationInfo importRoot(@PathVariable String ns, @RequestBody ImportRequest body) {
    byte[] polin = base64(body.polinBase64(), "polinBase64");
    ExpectedManifest manifest = manifest(body.manifestBase64());
    return service.bootstrap(ns, required(body.generation(), "generation"), polin, manifest);
  }

  @PostMapping("/generations")
  @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
  public LeaseResponse begin(@PathVariable String ns, @RequestBody BeginRequest body) {
    Optional<byte[]> pinned =
        body.polinBase64() == null
            ? Optional.empty()
            : Optional.of(base64(body.polinBase64(), "polinBase64"));
    Lease lease =
        service.begin(
            ns,
            required(body.parent(), "parent"),
            required(body.generation(), "generation"),
            pinned,
            manifest(body.manifestBase64()));
    return new LeaseResponse(lease.namespace(), lease.generation(), lease.parent(), lease.fence());
  }

  @PostMapping("/generations/{gen}/requests:raw")
  public ApplyResponse applyRaw(
      @PathVariable String ns, @PathVariable String gen, @RequestBody RawRequest body) {
    byte[] raw = hex(body.recordHex(), "recordHex", TransactionRecord.LENGTH);
    Applied applied =
        service.apply(lease(ns, gen, body.fence()), new TransactionRecord(raw), false);
    return response(applied, raw, false);
  }

  @PostMapping("/generations/{gen}/requests")
  public ApplyResponse applyTyped(
      @PathVariable String ns, @PathVariable String gen, @RequestBody TypedRequest body) {
    if (body.request() == null) {
      throw new TypedEnvelopeException("request is required");
    }
    TransactionRecord record = TypedCodec.encode(body.request());
    Applied applied = service.apply(lease(ns, gen, body.fence()), record, true);
    return response(applied, record.bytes(), true);
  }

  /**
   * Applies a whole TXNIN file in physical order, one durable commit per record, exactly as the raw
   * endpoint would. A failure part-way leaves the already committed prefix in place (and is
   * reported as 409); nothing is rolled back across records because the legacy contract commits per
   * request.
   */
  @PostMapping("/generations/{gen}/batch")
  public BatchResponse applyBatch(
      @PathVariable String ns, @PathVariable String gen, @RequestBody BatchRequest body) {
    byte[] txnin = base64(body.txninBase64(), "txninBase64");
    List<TransactionRecord> records;
    try {
      records = Records.transactions(txnin);
    } catch (Records.PartialRecordException e) {
      throw new TypedEnvelopeException(e.getMessage());
    }
    Lease lease = lease(ns, gen, body.fence());
    byte[] resout = new byte[records.size() * 96];
    long first = 0;
    long last = 0;
    for (int i = 0; i < records.size(); i++) {
      Applied a = service.apply(lease, records.get(i), false);
      if (i == 0) {
        first = a.ordinal();
      }
      last = a.ordinal();
      System.arraycopy(a.evaluation().result().bytes(), 0, resout, i * 96, 96);
    }
    return new BatchResponse(
        records.size(), first, last, Base64.getEncoder().encodeToString(resout));
  }

  @PostMapping("/generations/{gen}/publish")
  public Receipt publish(
      @PathVariable String ns, @PathVariable String gen, @RequestBody PublishRequest body) {
    String mode = body.mode() == null ? "http" : body.mode();
    if (!mode.matches("[a-z0-9-]{1,32}")) {
      throw new TypedEnvelopeException("mode must match [a-z0-9-]{1,32}");
    }
    return service.publish(lease(ns, gen, body.fence()), mode);
  }

  @PostMapping("/generations/{gen}/discard")
  public GenerationInfo discard(
      @PathVariable String ns, @PathVariable String gen, @RequestBody FenceRequest body) {
    service.discard(lease(ns, gen, body.fence()));
    return store().info(ns, gen).orElseThrow();
  }

  /**
   * Takes over an abandoned pending generation (writer lease expired). The store refuses a live
   * writer (409 fenced), a manifest other than the pinned one, a changed rate table or contract
   * identity, or a durable prefix the running contract does not reproduce (409 checkpoint); on
   * success the response carries the new fence and the verified {@code lastOrdinal}.
   */
  @PostMapping("/generations/{gen}/claim")
  public ClaimResponse claim(
      @PathVariable String ns, @PathVariable String gen, @RequestBody ClaimRequest body) {
    GenerationStore.Claimed c = service.claim(ns, gen, manifest(body.manifestBase64()));
    return new ClaimResponse(
        c.lease().namespace(),
        c.lease().generation(),
        c.lease().parent(),
        c.lease().fence(),
        c.claims(),
        c.lastOrdinal(),
        c.typedRequests(),
        c.rawRequests(),
        c.committedRequestsSha256(),
        c.checkpoint());
  }

  /** Explicit alternative to a claim: discards an abandoned pending generation. */
  @PostMapping("/generations/{gen}/discard-abandoned")
  public GenerationInfo discardAbandoned(
      @PathVariable String ns,
      @PathVariable String gen,
      @RequestBody DiscardAbandonedRequest body) {
    service.discardAbandoned(ns, gen, required(body.reason(), "reason"));
    return store().info(ns, gen).orElseThrow();
  }

  // ------------------------------------------------------------ reads

  @GetMapping("/current")
  public CurrentResponse current(@PathVariable String ns) {
    String cur = store().current(ns).orElseThrow(() -> new NotFoundException(ns));
    return new CurrentResponse(ns, cur);
  }

  @GetMapping("/generations/{gen}")
  public GenerationInfo info(@PathVariable String ns, @PathVariable String gen) {
    return store().info(ns, gen).orElseThrow(() -> new NotFoundException(ns + "/" + gen));
  }

  /** The expected manifest pinned when the generation was created (any lifecycle status). */
  @GetMapping("/generations/{gen}/manifest")
  public ExpectedManifest manifest(@PathVariable String ns, @PathVariable String gen) {
    return store().manifest(ns, gen).orElseThrow(() -> new NotFoundException(ns + "/" + gen));
  }

  @GetMapping("/generations/{gen}/receipt")
  public Receipt receipt(@PathVariable String ns, @PathVariable String gen) {
    return store().receipt(ns, gen).orElseThrow(() -> new NotFoundException(ns + "/" + gen));
  }

  @GetMapping(
      value = "/generations/{gen}/polout",
      produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<byte[]> polout(@PathVariable String ns, @PathVariable String gen) {
    return octets(store().polout(ns, gen));
  }

  @GetMapping(
      value = "/generations/{gen}/resout",
      produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<byte[]> resout(@PathVariable String ns, @PathVariable String gen) {
    return octets(store().resout(ns, gen));
  }

  @GetMapping(
      value = "/generations/{gen}/requests",
      produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<byte[]> requests(@PathVariable String ns, @PathVariable String gen) {
    return octets(store().requests(ns, gen));
  }

  @GetMapping(
      value = "/generations/{gen}/peek/polout",
      produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<byte[]> peekPolout(@PathVariable String ns, @PathVariable String gen) {
    return octets(store().peekPolout(ns, gen));
  }

  @GetMapping(
      value = "/generations/{gen}/peek/resout",
      produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<byte[]> peekResout(@PathVariable String ns, @PathVariable String gen) {
    return octets(store().peekResout(ns, gen));
  }

  /** Committed TXNIN prefix of a pending generation, for resume reconciliation. */
  @GetMapping(
      value = "/generations/{gen}/peek/requests",
      produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<byte[]> peekRequests(@PathVariable String ns, @PathVariable String gen) {
    return octets(store().peekRequests(ns, gen));
  }

  /** Current bytes of one policy in the namespace's current published generation. */
  @GetMapping("/policies/{id}")
  public PolicyResponse policy(@PathVariable String ns, @PathVariable String id) {
    String cur = store().current(ns).orElseThrow(() -> new NotFoundException(ns));
    return policyIn(ns, cur, id);
  }

  @GetMapping("/generations/{gen}/policies/{id}")
  public PolicyResponse policyIn(
      @PathVariable String ns, @PathVariable String gen, @PathVariable String id) {
    if (id.length() != 8) {
      throw new TypedEnvelopeException("policy id must be 8 characters");
    }
    GenerationInfo info =
        store().info(ns, gen).orElseThrow(() -> new NotFoundException(ns + "/" + gen));
    if (info.status() != GenerationStatus.PUBLISHED) {
      throw new GenerationStore.GenerationException(gen + " is not published");
    }
    PolicyRecord p =
        store().policy(ns, gen, Cp037.encode(id)).orElseThrow(() -> new NotFoundException(id));
    return new PolicyResponse(
        ns,
        gen,
        id,
        Hex.of(p.bytes()),
        p.issue(),
        p.date(),
        p.seq(),
        p.face(),
        p.cash(),
        p.loan(),
        Hex.of(p.last()));
  }

  // ------------------------------------------------------------ helpers

  private Lease lease(String ns, String gen, long fence) {
    GenerationInfo info =
        store().info(ns, gen).orElseThrow(() -> new NotFoundException(ns + "/" + gen));
    if (info.status() != GenerationStatus.PENDING) {
      throw new GenerationStore.FencedException(gen + " is " + info.status() + ", not pending");
    }
    return new Lease(ns, gen, info.parent(), fence);
  }

  private static ApplyResponse response(Applied applied, byte[] request, boolean typed) {
    byte[] result = applied.evaluation().result().bytes();
    return new ApplyResponse(
        applied.ordinal(),
        typed,
        applied.evaluation().status().name(),
        applied.evaluation().accepted(),
        Hex.of(request),
        Hex.of(result),
        TypedResult.of(applied.evaluation().result()));
  }

  private static ResponseEntity<byte[]> octets(byte[] body) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
  }

  static ExpectedManifest manifest(String base64) {
    try {
      return ExpectedManifest.parse(base64(base64, "manifestBase64"));
    } catch (RuntimeException e) {
      if (e instanceof TypedEnvelopeException) {
        throw e;
      }
      throw new TypedEnvelopeException(
          "manifest is not a valid expected manifest: " + e.getMessage());
    }
  }

  static byte[] base64(String value, String field) {
    if (value == null) {
      throw new TypedEnvelopeException(field + " is required");
    }
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw new TypedEnvelopeException(field + " is not valid base64");
    }
  }

  static byte[] hex(String value, String field, int length) {
    if (value == null) {
      throw new TypedEnvelopeException(field + " is required");
    }
    byte[] raw;
    try {
      raw = Hex.parse(value);
    } catch (IllegalArgumentException e) {
      throw new TypedEnvelopeException(field + " is not valid hex");
    }
    if (raw.length != length) {
      throw new TypedEnvelopeException(field + " must be exactly " + length + " bytes");
    }
    return raw;
  }

  private static String required(String value, String field) {
    if (value == null || value.isEmpty()) {
      throw new TypedEnvelopeException(field + " is required");
    }
    return value;
  }
}
