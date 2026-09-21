package insurance.app.persistence;

import insurance.contract.v001.Evaluation;
import insurance.contract.v001.PolicyTable;
import insurance.contract.v001.Status;
import insurance.ledger.BuildIdentity;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.Json;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.ledger.generation.GenerationInfo;
import insurance.ledger.generation.GenerationStatus;
import insurance.ledger.generation.GenerationStore;
import insurance.ledger.generation.ReceiptContext;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL generation store.
 *
 * <p>Writer fencing: {@code begin} allocates a fence from a database sequence and stores it on the
 * generation row; every {@code commit} is one transaction whose first statement is {@code UPDATE
 * generation SET last_ordinal = last_ordinal + 1 ... WHERE status = 'PENDING' AND fence = ?}. The
 * row lock serialises writers, the returned counter is the contiguous ordinal, and a zero row count
 * means the lease is stale or the generation is no longer pending.
 *
 * <p>Publication: one transaction locks the generation row and the namespace row ({@code FOR
 * UPDATE}), rebuilds the outputs from the persisted entries and policy state, validates them
 * against the pinned manifest, inserts the immutable output row, flips the status and moves the
 * namespace pointer with {@code WHERE current_generation_id = <expected parent>}. Any failure rolls
 * everything back and the generation is discarded in a separate transaction. A concurrent {@code
 * commit} blocks on the row lock and, once the publication has committed, sees {@code status <>
 * 'PENDING'} and is fenced. Published rows are protected by database triggers against INSERT,
 * UPDATE and DELETE.
 *
 * <p>Restart: {@link #open} discards every PENDING generation and verifies the current ancestry of
 * every namespace (status, receipt hashes recomputed from the stored bytes, contiguous ordinals);
 * any inconsistency fails the open. Durability is PostgreSQL's; no power-loss test was run.
 */
public final class JdbcGenerationStore implements GenerationStore {

  public static final class IntegrityException extends GenerationException {
    private static final long serialVersionUID = 1L;

    public IntegrityException(String message) {
      super(message);
    }
  }

  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final GenerationRepository generations;
  private final List<String> discardedOnOpen = new ArrayList<>();

  private JdbcGenerationStore(JdbcClient db, TransactionTemplate tx, GenerationRepository repo) {
    this.db = db;
    this.tx = tx;
    this.generations = repo;
  }

  public static JdbcGenerationStore open(
      JdbcClient db, TransactionTemplate tx, GenerationRepository repo) {
    JdbcGenerationStore store = new JdbcGenerationStore(db, tx, repo);
    store.tx.executeWithoutResult(
        s -> {
          for (GenerationRow row : repo.findByStatus(GenerationStatus.PENDING.name())) {
            store.markDiscarded(row.id());
            store.discardedOnOpen.add(row.namespace() + "/" + row.name());
          }
        });
    for (String ns : store.namespaces()) {
      store.verifyAncestry(ns);
    }
    return store;
  }

  public List<String> discardedOnOpen() {
    return List.copyOf(discardedOnOpen);
  }

  // ---------------------------------------------------------------- lifecycle

  @Override
  public GenerationInfo bootstrap(String namespace, String generation, List<PolicyRecord> masters) {
    validateName(namespace);
    validateName(generation);
    PolicyTable table = PolicyTable.load(masters);
    byte[] seed = table.size() == 0 ? new byte[0] : Records.join(table.rows());
    return tx.execute(
        s -> {
          db.sql(
                  "INSERT INTO namespace (name, current_generation_id) VALUES (?, NULL)"
                      + " ON CONFLICT (name) DO NOTHING")
              .param(namespace)
              .update();
          Long current =
              db.sql("SELECT current_generation_id FROM namespace WHERE name = ? FOR UPDATE")
                  .param(namespace)
                  .query(Long.class)
                  .optional()
                  .orElse(null);
          if (current != null) {
            throw new CasException(namespace + " already has a current generation");
          }
          long id = insertGeneration(namespace, generation, null, 0L, table, seed);
          insertPolicyState(id, table);
          ExpectedManifest manifest =
              new ExpectedManifest(
                  ExpectedManifest.SCHEMA,
                  "bootstrap",
                  table.size(),
                  0,
                  Sha256.of(seed),
                  Sha256.of(new byte[0]),
                  null,
                  null);
          GenerationInfo info =
              new GenerationInfo(
                  namespace,
                  generation,
                  null,
                  GenerationStatus.PUBLISHED,
                  0,
                  table.size(),
                  0,
                  0,
                  0,
                  Sha256.of(seed));
          Receipt receipt =
              new ReceiptContext("bootstrap", "n/a", "n/a", "n/a")
                  .complete(
                      info,
                      manifest,
                      seed,
                      new byte[0],
                      seed,
                      new byte[0],
                      BuildIdentity.runtime());
          insertOutput(id, seed, new byte[0], new byte[0], receipt);
          flipToPublished(id, 0L);
          int moved =
              db.sql(
                      "UPDATE namespace SET current_generation_id = ? WHERE name = ?"
                          + " AND current_generation_id IS NULL")
                  .params(id, namespace)
                  .update();
          if (moved != 1) {
            throw new CasException(namespace + " current pointer changed during bootstrap");
          }
          return info;
        });
  }

  @Override
  public Lease begin(String namespace, String parent, String generation) {
    validateName(generation);
    GenerationRow parentRow = reachableRow(namespace, parent);
    byte[] seed = output(parentRow.id(), "state");
    PolicyTable table = PolicyTable.load(Records.policies(seed));
    try {
      return tx.execute(
          s -> {
            long fence =
                db.sql("SELECT nextval('generation_fence_seq')").query(Long.class).single();
            long id = insertGeneration(namespace, generation, parentRow.id(), fence, table, seed);
            insertPolicyState(id, table);
            return new Lease(namespace, generation, parent, fence);
          });
    } catch (DuplicateKeyException e) {
      throw new GenerationException(generation + " already exists in " + namespace);
    }
  }

  @Override
  public Optional<PolicyRecord> policy(String namespace, String generation, byte[] id) {
    GenerationRow row =
        generations
            .findByNamespaceAndName(namespace, generation)
            .orElseThrow(() -> new GenerationException(generation + " does not exist"));
    if (row.generationStatus() == GenerationStatus.PENDING) {
      return db.sql("SELECT bytes FROM policy_state WHERE generation_id = ? AND policy_id = ?")
          .params(row.id(), id)
          .query(byte[].class)
          .optional()
          .map(PolicyRecord::new);
    }
    if (!reachable(namespace).contains(row.id())) {
      throw new GenerationException(generation + " is not a published generation of " + namespace);
    }
    return PolicyTable.ofValidated(Records.policies(output(row.id(), "state"))).find(id);
  }

  @Override
  public Applied commit(
      Lease lease,
      TransactionRecord request,
      Function<Optional<PolicyRecord>, Evaluation> evaluator,
      boolean typed) {
    return tx.execute(
        s -> {
          Long ordinal =
              db.sql(
                      "UPDATE generation SET last_ordinal = last_ordinal + 1,"
                          + " typed_requests = typed_requests + ?, raw_requests = raw_requests + ?"
                          + " WHERE namespace = ? AND name = ? AND status = 'PENDING' AND fence = ?"
                          + " RETURNING last_ordinal")
                  .params(
                      typed ? 1 : 0,
                      typed ? 0 : 1,
                      lease.namespace(),
                      lease.generation(),
                      lease.fence())
                  .query(Long.class)
                  .optional()
                  .orElseThrow(
                      () ->
                          new FencedException(
                              lease.generation()
                                  + " is not an open pending generation held by"
                                  + " fence "
                                  + lease.fence()));
          long id = idOf(lease.namespace(), lease.generation());
          Optional<PolicyRecord> master =
              db.sql("SELECT bytes FROM policy_state WHERE generation_id = ? AND policy_id = ?")
                  .params(id, request.id())
                  .query(byte[].class)
                  .optional()
                  .map(PolicyRecord::new);
          Evaluation evaluation = evaluator.apply(master);
          byte[] successor = evaluation.accepted() ? evaluation.master().bytes() : null;
          db.sql(
                  "INSERT INTO generation_entry (generation_id, ordinal, request, result, typed,"
                      + " successor) VALUES (?, ?, ?, ?, ?, ?)")
              .params(id, ordinal, request.bytes(), evaluation.result().bytes(), typed, successor)
              .update();
          if (successor != null) {
            int n =
                db.sql(
                        "UPDATE policy_state SET bytes = ?"
                            + " WHERE generation_id = ? AND policy_id = ?")
                    .params(successor, id, evaluation.master().id())
                    .update();
            if (n != 1) {
              throw new IntegrityException(
                  "accepted request for unknown policy " + Hex.of(evaluation.master().id()));
            }
          }
          return new Applied(ordinal, evaluation);
        });
  }

  @Override
  public Receipt publish(Lease lease, ExpectedManifest manifest, ReceiptContext context) {
    try {
      return tx.execute(
          s -> {
            GenerationRow row =
                db.sql(
                        "SELECT id, namespace, name, parent_id, status, fence, policies_count,"
                            + " last_ordinal, typed_requests, raw_requests, seed_polin_sha256"
                            + " FROM generation WHERE namespace = ? AND name = ? FOR UPDATE")
                    .params(lease.namespace(), lease.generation())
                    .query(GenerationRow.class)
                    .optional()
                    .orElseThrow(() -> new FencedException(lease.generation() + " does not exist"));
            if (row.generationStatus() != GenerationStatus.PENDING
                || row.fence() != lease.fence()) {
              throw new FencedException(
                  lease.generation() + " is " + row.status() + " or held by another fence");
            }
            Long current =
                db.sql("SELECT current_generation_id FROM namespace WHERE name = ? FOR UPDATE")
                    .param(lease.namespace())
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (current == null || !current.equals(row.parentId())) {
              throw new CasException(
                  "current generation of "
                      + lease.namespace()
                      + " is no longer "
                      + lease.parent()
                      + "; expected-parent CAS failed");
            }
            byte[] seed = seed(row.id());
            Rebuilt r = rebuild(row, seed);
            if (!manifest.polinSha256().equals(Sha256.of(seed))) {
              throw new GenerationException(
                  "pinned POLIN hash does not match the seeded parent bytes");
            }
            if (!manifest.txninSha256().equals(Sha256.of(r.requests))) {
              throw new GenerationException(
                  "pinned TXNIN hash does not match the applied requests");
            }
            manifest.verifyOutputs(r.state, r.results, 0);
            GenerationInfo published = row.toInfo(lease.parent()).with(GenerationStatus.PUBLISHED);
            Receipt receipt =
                context.complete(
                    published,
                    manifest,
                    seed,
                    r.requests,
                    r.state,
                    r.results,
                    BuildIdentity.runtime());
            insertOutput(row.id(), r.state, r.results, r.requests, receipt);
            flipToPublished(row.id(), lease.fence());
            int moved =
                db.sql(
                        "UPDATE namespace SET current_generation_id = ? WHERE name = ?"
                            + " AND current_generation_id = ?")
                    .params(row.id(), lease.namespace(), row.parentId())
                    .update();
            if (moved != 1) {
              throw new CasException("expected-parent CAS failed at pointer update");
            }
            return receipt;
          });
    } catch (FencedException e) {
      throw e;
    } catch (GenerationException | ExpectedManifest.ManifestException e) {
      discardQuietly(lease);
      throw e;
    }
  }

  @Override
  public void discard(Lease lease) {
    Integer n =
        tx.execute(
            s ->
                db.sql(
                        "UPDATE generation SET status = 'DISCARDED'"
                            + " WHERE namespace = ? AND name = ?"
                            + " AND status = 'PENDING' AND fence = ?")
                    .params(lease.namespace(), lease.generation(), lease.fence())
                    .update());
    if (n == null || n != 1) {
      throw new FencedException(lease.generation() + " is not an open pending generation");
    }
  }

  private void discardQuietly(Lease lease) {
    tx.executeWithoutResult(
        s ->
            db.sql(
                    "UPDATE generation SET status = 'DISCARDED' WHERE namespace = ? AND name = ?"
                        + " AND status = 'PENDING' AND fence = ?")
                .params(lease.namespace(), lease.generation(), lease.fence())
                .update());
  }

  // ---------------------------------------------------------------- reads

  @Override
  public Optional<String> current(String namespace) {
    return db.sql(
            "SELECT g.name FROM namespace n JOIN generation g ON g.id = n.current_generation_id"
                + " WHERE n.name = ?")
        .param(namespace)
        .query(String.class)
        .optional();
  }

  @Override
  public Optional<GenerationInfo> info(String namespace, String generation) {
    return generations
        .findByNamespaceAndName(namespace, generation)
        .map(
            row -> {
              GenerationInfo info = row.toInfo(parentName(row));
              if (info.status() == GenerationStatus.PUBLISHED
                  && !reachable(namespace).contains(row.id())) {
                return info.with(GenerationStatus.DISCARDED);
              }
              return info;
            });
  }

  @Override
  public byte[] polout(String namespace, String generation) {
    return output(reachableRow(namespace, generation).id(), "state");
  }

  @Override
  public byte[] resout(String namespace, String generation) {
    return output(reachableRow(namespace, generation).id(), "results");
  }

  @Override
  public byte[] requests(String namespace, String generation) {
    return output(reachableRow(namespace, generation).id(), "requests");
  }

  @Override
  public Optional<Receipt> receipt(String namespace, String generation) {
    Optional<GenerationRow> row = generations.findByNamespaceAndName(namespace, generation);
    if (row.isEmpty() || !reachable(namespace).contains(row.get().id())) {
      return Optional.empty();
    }
    return Optional.of(receiptOf(row.get().id()));
  }

  @Override
  public byte[] peekPolout(String namespace, String generation) {
    GenerationRow row = pendingRow(namespace, generation);
    List<byte[]> rows =
        db.sql("SELECT bytes FROM policy_state WHERE generation_id = ? ORDER BY position")
            .param(row.id())
            .query(byte[].class)
            .list();
    return concat(rows, PolicyRecord.LENGTH);
  }

  @Override
  public byte[] peekResout(String namespace, String generation) {
    GenerationRow row = pendingRow(namespace, generation);
    List<byte[]> rows =
        db.sql("SELECT result FROM generation_entry WHERE generation_id = ? ORDER BY ordinal")
            .param(row.id())
            .query(byte[].class)
            .list();
    return concat(rows, ResultRecord.LENGTH);
  }

  @Override
  public List<String> namespaces() {
    return db.sql("SELECT name FROM namespace ORDER BY name").query(String.class).list();
  }

  // ---------------------------------------------------------------- integrity

  /** Ids of the generations on the accepted ancestry current -> parent -> root, all verified. */
  Set<Long> reachable(String namespace) {
    return verifyAncestry(namespace);
  }

  private Set<Long> verifyAncestry(String namespace) {
    Set<Long> seen = new HashSet<>();
    Long id =
        db.sql("SELECT current_generation_id FROM namespace WHERE name = ?")
            .param(namespace)
            .query(Long.class)
            .optional()
            .orElse(null);
    while (id != null) {
      Long cursor = id;
      GenerationRow row =
          generations
              .findById(cursor)
              .orElseThrow(
                  () ->
                      new IntegrityException(
                          namespace
                              + ": current ancestry references missing generation "
                              + cursor));
      if (row.generationStatus() != GenerationStatus.PUBLISHED) {
        throw new IntegrityException(namespace + "/" + row.name() + ": status " + row.status());
      }
      Long entries =
          db.sql("SELECT count(*) FROM generation_entry WHERE generation_id = ?")
              .param(row.id())
              .query(Long.class)
              .single();
      if (entries != row.lastOrdinal()) {
        throw new IntegrityException(
            namespace + "/" + row.name() + ": entry count " + entries + " != " + row.lastOrdinal());
      }
      Long maxOrdinal =
          db.sql("SELECT coalesce(max(ordinal), 0) FROM generation_entry WHERE generation_id = ?")
              .param(row.id())
              .query(Long.class)
              .single();
      if (maxOrdinal != row.lastOrdinal()) {
        throw new IntegrityException(namespace + "/" + row.name() + ": ordinals not contiguous");
      }
      Receipt receipt = receiptOf(row.id());
      byte[] state = output(row.id(), "state");
      byte[] results = output(row.id(), "results");
      byte[] requests = output(row.id(), "requests");
      if (!receipt.poloutSha256().equals(Sha256.of(state))
          || !receipt.resoutSha256().equals(Sha256.of(results))
          || !receipt.txninSha256().equals(Sha256.of(requests))
          || !receipt.polinSha256().equals(row.seedPolinSha256())) {
        throw new IntegrityException(
            namespace + "/" + row.name() + ": receipt hashes do not match stored bytes");
      }
      if (results.length / ResultRecord.LENGTH != row.lastOrdinal()) {
        throw new IntegrityException(namespace + "/" + row.name() + ": result count != ordinal");
      }
      if (!seen.add(row.id())) {
        throw new IntegrityException(namespace + ": ancestry cycle at " + row.name());
      }
      id = row.parentId();
    }
    return seen;
  }

  private record Rebuilt(byte[] state, byte[] results, byte[] requests) {}

  private Rebuilt rebuild(GenerationRow row, byte[] seed) {
    List<Entry> entries =
        db.sql(
                "SELECT ordinal, request, result, successor FROM generation_entry"
                    + " WHERE generation_id = ? ORDER BY ordinal")
            .param(row.id())
            .query(Entry.class)
            .list();
    if (entries.size() != row.lastOrdinal()) {
      throw new IntegrityException(
          "generation has " + entries.size() + " entries but last ordinal is " + row.lastOrdinal());
    }
    PolicyTable replay = PolicyTable.ofValidated(Records.policies(seed));
    byte[] results = new byte[entries.size() * ResultRecord.LENGTH];
    byte[] requests = new byte[entries.size() * TransactionRecord.LENGTH];
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      if (e.ordinal() != i + 1) {
        throw new IntegrityException("ordinal gap: expected " + (i + 1) + " found " + e.ordinal());
      }
      System.arraycopy(e.request(), 0, requests, i * TransactionRecord.LENGTH, 40);
      System.arraycopy(e.result(), 0, results, i * ResultRecord.LENGTH, ResultRecord.LENGTH);
      ResultRecord result = new ResultRecord(e.result());
      boolean okay = Status.OKAY.name().equals(result.status());
      if (okay != (e.successor() != null)) {
        throw new IntegrityException("entry " + e.ordinal() + " successor/status disagree");
      }
      if (okay) {
        replay.replace(new PolicyRecord(e.successor()));
      }
    }
    byte[] replayed = replay.size() == 0 ? new byte[0] : Records.join(replay.rows());
    List<byte[]> stateRows =
        db.sql("SELECT bytes FROM policy_state WHERE generation_id = ? ORDER BY position")
            .param(row.id())
            .query(byte[].class)
            .list();
    byte[] state = concat(stateRows, PolicyRecord.LENGTH);
    if (!Arrays.equals(state, replayed)) {
      throw new IntegrityException("policy state disagrees with the replayed entry successors");
    }
    return new Rebuilt(state, results, requests);
  }

  record Entry(long ordinal, byte[] request, byte[] result, byte[] successor) {}

  // ---------------------------------------------------------------- helpers

  private long insertGeneration(
      String namespace,
      String generation,
      Long parentId,
      long fence,
      PolicyTable table,
      byte[] seed) {
    return db.sql(
            "INSERT INTO generation (namespace, name, parent_id, status, fence,"
                + " policies_count, seed_polin_sha256, seed)"
                + " VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?)"
                + " RETURNING id")
        .params(namespace, generation, parentId, fence, table.size(), Sha256.of(seed), seed)
        .query(Long.class)
        .single();
  }

  private void insertPolicyState(long id, PolicyTable table) {
    List<PolicyRecord> rows = table.rows();
    for (int i = 0; i < rows.size(); i++) {
      db.sql(
              "INSERT INTO policy_state (generation_id, policy_id, position, bytes)"
                  + " VALUES (?, ?, ?, ?)")
          .params(id, rows.get(i).id(), i, rows.get(i).bytes())
          .update();
    }
  }

  private void insertOutput(long id, byte[] state, byte[] results, byte[] requests, Receipt r) {
    db.sql(
            "INSERT INTO generation_output (generation_id, state, results, requests, receipt)"
                + " VALUES (?, ?, ?, ?, ?)")
        .params(id, state, results, requests, new String(Json.bytes(r), StandardCharsets.UTF_8))
        .update();
  }

  private void flipToPublished(long id, long fence) {
    int n =
        db.sql(
                "UPDATE generation SET status = 'PUBLISHED' WHERE id = ? AND status = 'PENDING'"
                    + " AND fence = ?")
            .params(id, fence)
            .update();
    if (n != 1) {
      throw new FencedException("generation " + id + " left PENDING before publication");
    }
  }

  private void markDiscarded(long id) {
    db.sql("UPDATE generation SET status = 'DISCARDED' WHERE id = ? AND status = 'PENDING'")
        .param(id)
        .update();
  }

  private long idOf(String namespace, String generation) {
    return generations
        .findByNamespaceAndName(namespace, generation)
        .orElseThrow(() -> new GenerationException(generation + " does not exist"))
        .id();
  }

  private GenerationRow reachableRow(String namespace, String generation) {
    GenerationRow row =
        generations
            .findByNamespaceAndName(namespace, generation)
            .orElseThrow(
                () ->
                    new GenerationException(
                        generation + " is not a published generation of " + namespace));
    if (!reachable(namespace).contains(row.id())) {
      throw new GenerationException(
          generation + " is not a published generation reachable from current of " + namespace);
    }
    return row;
  }

  private GenerationRow pendingRow(String namespace, String generation) {
    GenerationRow row =
        generations
            .findByNamespaceAndName(namespace, generation)
            .orElseThrow(
                () -> new GenerationException(generation + " is not an open pending generation"));
    if (row.generationStatus() != GenerationStatus.PENDING) {
      throw new GenerationException(generation + " is not an open pending generation");
    }
    return row;
  }

  private String parentName(GenerationRow row) {
    if (row.parentId() == null) {
      return null;
    }
    return generations.findById(row.parentId()).map(GenerationRow::name).orElse(null);
  }

  private byte[] seed(long id) {
    return db.sql("SELECT seed FROM generation WHERE id = ?")
        .param(id)
        .query(byte[].class)
        .single();
  }

  private byte[] output(long id, String column) {
    return db.sql("SELECT " + column + " FROM generation_output WHERE generation_id = ?")
        .param(id)
        .query(byte[].class)
        .optional()
        .orElseThrow(() -> new IntegrityException("generation " + id + " has no output row"));
  }

  private Receipt receiptOf(long id) {
    String json =
        db.sql("SELECT receipt FROM generation_output WHERE generation_id = ?")
            .param(id)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new IntegrityException("generation " + id + " has no receipt"));
    return Json.read(json.getBytes(StandardCharsets.UTF_8), Receipt.class);
  }

  private static byte[] concat(List<byte[]> rows, int length) {
    byte[] out = new byte[rows.size() * length];
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).length != length) {
        throw new IntegrityException("stored record has length " + rows.get(i).length);
      }
      System.arraycopy(rows.get(i), 0, out, i * length, length);
    }
    return out;
  }

  private static void validateName(String name) {
    if (name == null || !name.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new GenerationException("invalid namespace/generation name: " + name);
    }
  }
}
