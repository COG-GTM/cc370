package insurance.app.persistence;

import insurance.contract.v001.ContractV001;
import insurance.contract.v001.Evaluation;
import insurance.contract.v001.PolicyTable;
import insurance.ledger.BuildIdentity;
import insurance.ledger.ContractBinding;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.Json;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.ledger.generation.GenerationInfo;
import insurance.ledger.generation.GenerationStatus;
import insurance.ledger.generation.GenerationStore;
import insurance.ledger.generation.PrefixVerifier;
import insurance.ledger.generation.ReceiptContext;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
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
 * <p>Manifest: the expected manifest is pinned on the generation row at {@code bootstrap}/{@code
 * begin} (after the seed and rate-table checks) and is immutable; {@code publish} validates against
 * the pinned copy only.
 *
 * <p>Ownership across instances: every store instance has a writer id; the pending generations it
 * begins or claims carry that id and a lease that a heartbeat renews while the instance is alive.
 * Every lease decision is taken by the database against its own clock inside the transaction that
 * depends on it, after taking the generation row lock and against {@code clock_timestamp()} rather
 * than {@code now()}: {@code now()} is frozen at transaction start, and a bare {@code UPDATE} that
 * queued on the row lock while the lease lapsed would otherwise still see the lease as live. {@code
 * commit}, {@code publish}, {@code discard} and the heartbeat require {@code lease_expires_at >
 * clock_timestamp()} in their fenced predicate, so an expired writer can neither renew nor write
 * again; {@code claim} requires {@code lease_expires_at <= clock_timestamp()} under {@code FOR
 * UPDATE}, so a live writer is never displaced and two claimants serialise on the row (the second
 * sees the first's fresh lease and is refused). {@link #open} never discards: expired pending
 * generations are reported ({@link #abandonedOnOpen}) and stay claimable or explicitly discardable.
 *
 * <p>Claim: under the row lock the pinned manifest, the running rate table and the pinned contract
 * identity ({@link ContractBinding#identity}) are checked, the parent must still be the current
 * generation, and the durable prefix is established with {@link PrefixVerifier} (every stored
 * request re-evaluated by the running contract, result/successor/policy-state bytes and the hash
 * chain compared). Only then does one {@code UPDATE} move fence, writer and lease and count the
 * claim; the transition is recorded in {@code generation_claim}. The new writer continues at {@code
 * last_ordinal + 1}. A prefix that does not verify leaves the row untouched and throws {@link
 * CheckpointException}; nothing is repaired or rerun.
 *
 * <p>Restart also verifies the current ancestry of every namespace (status, receipt hashes
 * recomputed from the stored bytes, contiguous ordinals: {@code count = max = last_ordinal} over a
 * unique {@code ordinal >= 1} key is a proof of the prefix 1..n); any inconsistency fails the open.
 * Durability is PostgreSQL's; no power-loss test was run.
 */
public final class JdbcGenerationStore implements GenerationStore {
  private static final Logger LOG = LoggerFactory.getLogger(JdbcGenerationStore.class);

  public static final class IntegrityException extends GenerationException {
    private static final long serialVersionUID = 1L;

    public IntegrityException(String message) {
      super(message);
    }
  }

  public static final Duration DEFAULT_LEASE = Duration.ofSeconds(60);

  /**
   * Lifecycle boundaries the process-kill tests halt the JVM at. Inside a transaction ("before")
   * the database rolls the work back; after one ("after") the work is durable but the caller never
   * receives the response.
   */
  public enum Boundary {
    BEFORE_COMMIT,
    AFTER_COMMIT,
    BEFORE_PUBLISH_FLIP,
    AFTER_PUBLISH
  }

  /** Test hook invoked at every {@link Boundary}; the default does nothing. */
  @FunctionalInterface
  public interface FaultPoint {
    FaultPoint NONE = (boundary, ordinal) -> {};

    /** {@code ordinal} is the ordinal being committed, or the last ordinal at publication. */
    void reached(Boundary boundary, long ordinal);
  }

  private final JdbcClient db;
  private final TransactionTemplate tx;
  private final GenerationRepository generations;
  private final String writerId;
  private final Duration lease;
  private final ContractBinding binding;
  private final List<String> abandonedOnOpen = new ArrayList<>();
  private final List<String> liveOnOpen = new ArrayList<>();
  private final ScheduledExecutorService heartbeat;
  private final AtomicLong heartbeatFailures = new AtomicLong();
  private final FaultPoint faults;

  private JdbcGenerationStore(
      JdbcClient db,
      TransactionTemplate tx,
      GenerationRepository repo,
      Duration lease,
      FaultPoint faults,
      ContractBinding binding) {
    this.db = db;
    this.tx = tx;
    this.generations = repo;
    this.lease = lease;
    this.faults = faults;
    this.binding = binding;
    this.writerId = ProcessHandle.current().pid() + "@" + hostName() + "/" + UUID.randomUUID();
    this.heartbeat =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "generation-lease-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  public static JdbcGenerationStore open(
      JdbcClient db, TransactionTemplate tx, GenerationRepository repo) {
    return open(db, tx, repo, DEFAULT_LEASE);
  }

  /**
   * Opens the store: reports pending generations (live writer or abandoned) without touching them,
   * verifies every namespace's current ancestry and starts the lease heartbeat for the generations
   * this instance will begin or claim.
   */
  public static JdbcGenerationStore open(
      JdbcClient db, TransactionTemplate tx, GenerationRepository repo, Duration lease) {
    return open(db, tx, repo, lease, FaultPoint.NONE);
  }

  public static JdbcGenerationStore open(
      JdbcClient db,
      TransactionTemplate tx,
      GenerationRepository repo,
      Duration lease,
      FaultPoint faults) {
    return open(db, tx, repo, lease, faults, ContractBinding.of(ContractV001.frozen()));
  }

  public static JdbcGenerationStore open(
      JdbcClient db,
      TransactionTemplate tx,
      GenerationRepository repo,
      Duration lease,
      FaultPoint faults,
      ContractBinding binding) {
    JdbcGenerationStore store = new JdbcGenerationStore(db, tx, repo, lease, faults, binding);
    List<PendingLease> pending =
        db.sql(
                "SELECT id, namespace, name, writer_id,"
                    + " (lease_expires_at IS NOT NULL"
                    + " AND lease_expires_at > clock_timestamp()) AS live"
                    + " FROM generation WHERE status = 'PENDING' ORDER BY id")
            .query(PendingLease.class)
            .list();
    for (PendingLease row : pending) {
      (row.live() ? store.liveOnOpen : store.abandonedOnOpen)
          .add(row.namespace() + "/" + row.name());
    }
    if (!store.abandonedOnOpen.isEmpty()) {
      LOG.warn(
          "abandoned pending generations left in place for claim or discard: {}",
          store.abandonedOnOpen);
    }
    for (String ns : store.namespaces()) {
      store.verifyAncestry(ns);
    }
    long period = Math.max(1, lease.toMillis() / 3);
    store.heartbeat.scheduleAtFixedRate(
        store::heartbeatTick, period, period, TimeUnit.MILLISECONDS);
    return store;
  }

  record PendingLease(long id, String namespace, String name, String writerId, boolean live) {}

  /** Pending generations whose writer lease had expired at open: claimable or discardable. */
  public List<String> abandonedOnOpen() {
    return List.copyOf(abandonedOnOpen);
  }

  /** Pending generations of other live writers that this open() left untouched. */
  public List<String> liveOnOpen() {
    return List.copyOf(liveOnOpen);
  }

  public String writerId() {
    return writerId;
  }

  public ContractBinding binding() {
    return binding;
  }

  /**
   * One heartbeat run. A database outage must not end the heartbeat: a scheduled task that throws
   * is silently cancelled by the executor, so failures are counted and logged and the next tick
   * renews again once the database is back.
   */
  private void heartbeatTick() {
    try {
      renewLeases();
    } catch (DataAccessException | TransactionException e) {
      long n = heartbeatFailures.incrementAndGet();
      LOG.warn("lease heartbeat failed ({} so far): {}", n, e.getMessage());
    }
  }

  /** Heartbeat runs that could not reach the database since open. */
  public long heartbeatFailures() {
    return heartbeatFailures.get();
  }

  /**
   * Renews the still-live lease of every pending generation this instance owns; an already expired
   * lease is not resurrected (the generation is claimable by then).
   */
  public int renewLeases() {
    Integer n =
        tx.execute(
            s -> {
              db.sql(
                      "SELECT id FROM generation WHERE status = 'PENDING' AND writer_id = ?"
                          + " ORDER BY id FOR UPDATE")
                  .param(writerId)
                  .query(Long.class)
                  .list();
              return db.sql(
                      "UPDATE generation SET lease_expires_at = clock_timestamp() + ?::interval"
                          + " WHERE status = 'PENDING' AND writer_id = ?"
                          + " AND lease_expires_at > clock_timestamp()")
                  .params(lease.toMillis() + " milliseconds", writerId)
                  .update();
            });
    return n == null ? 0 : n;
  }

  /**
   * Takes the generation row lock before a fenced predicate is evaluated. A bare {@code UPDATE}
   * evaluates its {@code WHERE} before it queues on a lock held by another transaction and, when
   * that holder rolls back, proceeds on that stale evaluation; locking first makes the lease test
   * run against database time at the moment this transaction actually owns the row.
   */
  private void lockGeneration(String namespace, String generation) {
    db.sql("SELECT id FROM generation WHERE namespace = ? AND name = ? FOR UPDATE")
        .params(namespace, generation)
        .query(Long.class)
        .optional();
  }

  @Override
  public void close() {
    heartbeat.shutdownNow();
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "localhost";
    }
  }

  // ---------------------------------------------------------------- lifecycle

  @Override
  public GenerationInfo bootstrap(
      String namespace, String generation, List<PolicyRecord> masters, ExpectedManifest manifest) {
    validateName(namespace);
    validateName(generation);
    PolicyTable table = PolicyTable.load(masters);
    byte[] seed = table.size() == 0 ? new byte[0] : Records.join(table.rows());
    manifest.verifyInputs(seed, new byte[0]);
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
          long id = insertGeneration(namespace, generation, null, 0L, table, seed, manifest);
          insertPolicyState(id, table);
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
                  Sha256.of(seed),
                  ReceiptContext.manifestSha256(manifest));
          Receipt receipt =
              new ReceiptContext("bootstrap", "n/a", "n/a", manifest.ratesSha256())
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
  public Lease begin(
      String namespace, String parent, String generation, ExpectedManifest manifest) {
    validateName(generation);
    GenerationRow parentRow = reachableRow(namespace, parent);
    byte[] seed = output(parentRow.id(), "state");
    manifest.verifySeed(seed);
    PolicyTable table = PolicyTable.load(Records.policies(seed));
    try {
      return tx.execute(
          s -> {
            long fence =
                db.sql("SELECT nextval('generation_fence_seq')").query(Long.class).single();
            long id =
                insertGeneration(
                    namespace, generation, parentRow.id(), fence, table, seed, manifest);
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

  record Advanced(long id, long lastOrdinal, String checkpoint) {}

  @Override
  public Applied commit(
      Lease lease,
      TransactionRecord request,
      Function<Optional<PolicyRecord>, Evaluation> evaluator,
      boolean typed) {
    Applied applied =
        tx.execute(
            s -> {
              lockGeneration(lease.namespace(), lease.generation());
              Advanced adv =
                  db.sql(
                          "UPDATE generation SET last_ordinal = last_ordinal + 1,"
                              + " typed_requests = typed_requests + ?,"
                              + " raw_requests = raw_requests + ?,"
                              + " lease_expires_at = clock_timestamp() + ?::interval"
                              + " WHERE namespace = ? AND name = ? AND status = 'PENDING'"
                              + " AND fence = ? AND writer_id = ?"
                              + " AND lease_expires_at > clock_timestamp()"
                              + " RETURNING id, last_ordinal, checkpoint")
                      .params(
                          typed ? 1 : 0,
                          typed ? 0 : 1,
                          this.lease.toMillis() + " milliseconds",
                          lease.namespace(),
                          lease.generation(),
                          lease.fence(),
                          writerId)
                      .query(Advanced.class)
                      .optional()
                      .orElseThrow(
                          () ->
                              new FencedException(
                                  lease.generation()
                                      + " is not an open pending generation held by fence "
                                      + lease.fence()
                                      + " and writer "
                                      + writerId
                                      + " under a live lease"));
              long id = adv.id();
              long ordinal = adv.lastOrdinal();
              if (adv.checkpoint() == null) {
                throw new CheckpointException(
                    lease.generation() + " has no checkpoint; refusing to commit onto nothing");
              }
              Optional<PolicyRecord> master =
                  db.sql("SELECT bytes FROM policy_state WHERE generation_id = ? AND policy_id = ?")
                      .params(id, request.id())
                      .query(byte[].class)
                      .optional()
                      .map(PolicyRecord::new);
              Evaluation evaluation = evaluator.apply(master);
              byte[] successor = evaluation.accepted() ? evaluation.master().bytes() : null;
              String chain =
                  PrefixVerifier.chain(
                      adv.checkpoint(),
                      ordinal,
                      request.bytes(),
                      evaluation.result().bytes(),
                      typed,
                      successor);
              db.sql(
                      "INSERT INTO generation_entry (generation_id, ordinal, request, result,"
                          + " typed, successor, chain) VALUES (?, ?, ?, ?, ?, ?, ?)")
                  .params(
                      id,
                      ordinal,
                      request.bytes(),
                      evaluation.result().bytes(),
                      typed,
                      successor,
                      chain)
                  .update();
              int advanced =
                  db.sql("UPDATE generation SET checkpoint = ? WHERE id = ? AND last_ordinal = ?")
                      .params(chain, id, ordinal)
                      .update();
              if (advanced != 1) {
                throw new IntegrityException("checkpoint update lost the generation row");
              }
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
              faults.reached(Boundary.BEFORE_COMMIT, ordinal);
              return new Applied(ordinal, evaluation);
            });
    faults.reached(Boundary.AFTER_COMMIT, applied.ordinal());
    return applied;
  }

  @Override
  public Receipt publish(Lease lease, ReceiptContext context) {
    Receipt receipt;
    try {
      receipt =
          tx.execute(
              s -> {
                LockedRow locked = lockRow(lease.namespace(), lease.generation());
                GenerationRow row = locked.row();
                if (row.generationStatus() != GenerationStatus.PENDING
                    || row.fence() != lease.fence()
                    || !writerId.equals(row.writerId())
                    || !locked.live()) {
                  throw new FencedException(
                      lease.generation()
                          + " is "
                          + row.status()
                          + ", held by another writer, or its lease has expired");
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
                ExpectedManifest manifest = manifestOf(row.id(), row.manifestSha256());
                PrefixVerifier.Verified r = verifiedPrefix(row, seed);
                context.validatePublication(manifest, seed, r.requests(), r.state(), r.results());
                GenerationInfo published =
                    row.toInfo(lease.parent()).with(GenerationStatus.PUBLISHED);
                Receipt done =
                    context.complete(
                        published,
                        manifest,
                        seed,
                        r.requests(),
                        r.state(),
                        r.results(),
                        BuildIdentity.runtime());
                insertOutput(row.id(), r.state(), r.results(), r.requests(), done);
                faults.reached(Boundary.BEFORE_PUBLISH_FLIP, row.lastOrdinal());
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
                return done;
              });
    } catch (FencedException e) {
      throw e;
    } catch (GenerationException | ExpectedManifest.ManifestException e) {
      discardQuietly(lease);
      throw e;
    }
    faults.reached(Boundary.AFTER_PUBLISH, receipt.transactionsCount());
    return receipt;
  }

  @Override
  public void discard(Lease lease) {
    Integer n =
        tx.execute(
            s -> {
              lockGeneration(lease.namespace(), lease.generation());
              return db.sql(
                      "UPDATE generation SET status = 'DISCARDED',"
                          + " discard_reason = 'discarded by its writer'"
                          + " WHERE namespace = ? AND name = ?"
                          + " AND status = 'PENDING' AND fence = ? AND writer_id = ?"
                          + " AND lease_expires_at > clock_timestamp()")
                  .params(lease.namespace(), lease.generation(), lease.fence(), writerId)
                  .update();
            });
    if (n == null || n != 1) {
      throw new FencedException(
          lease.generation() + " is not an open pending generation held under a live lease");
    }
  }

  private void discardQuietly(Lease lease) {
    tx.executeWithoutResult(
        s -> {
          lockGeneration(lease.namespace(), lease.generation());
          db.sql(
                  "UPDATE generation SET status = 'DISCARDED',"
                      + " discard_reason = 'publication validation failed'"
                      + " WHERE namespace = ? AND name = ?"
                      + " AND status = 'PENDING' AND fence = ? AND writer_id = ?"
                      + " AND lease_expires_at > clock_timestamp()")
              .params(lease.namespace(), lease.generation(), lease.fence(), writerId)
              .update();
        });
  }

  @Override
  public void discardAbandoned(String namespace, String generation, String reason) {
    tx.executeWithoutResult(
        s -> {
          LockedRow locked = lockRow(namespace, generation);
          if (locked.row().generationStatus() != GenerationStatus.PENDING) {
            throw new GenerationException(
                generation + " is " + locked.row().status() + ", not pending");
          }
          if (locked.live()) {
            throw new FencedException(
                generation + " is held by a live writer " + locked.row().writerId());
          }
          markDiscarded(locked.row().id(), "abandoned, discarded explicitly: " + reason);
        });
  }

  // ---------------------------------------------------------------- claim

  record LockedRow(GenerationRow row, boolean live, java.sql.Timestamp leaseExpiresAt) {}

  /** Locks the generation row first, then reads it with the lease judged by the database clock. */
  private LockedRow lockRow(String namespace, String generation) {
    lockGeneration(namespace, generation);
    return db.sql(
            "SELECT id, namespace, name, parent_id, status, fence, policies_count,"
                + " last_ordinal, typed_requests, raw_requests, seed_polin_sha256,"
                + " manifest_sha256, contract_identity, checkpoint, claims, writer_id,"
                + " (lease_expires_at IS NOT NULL"
                + " AND lease_expires_at > clock_timestamp()) AS live,"
                + " lease_expires_at"
                + " FROM generation WHERE namespace = ? AND name = ? FOR UPDATE")
        .params(namespace, generation)
        .query(
            (rs, i) ->
                new LockedRow(
                    new GenerationRow(
                        rs.getLong("id"),
                        rs.getString("namespace"),
                        rs.getString("name"),
                        rs.getObject("parent_id", Long.class),
                        rs.getString("status"),
                        rs.getLong("fence"),
                        rs.getInt("policies_count"),
                        rs.getLong("last_ordinal"),
                        rs.getInt("typed_requests"),
                        rs.getInt("raw_requests"),
                        rs.getString("seed_polin_sha256"),
                        rs.getString("manifest_sha256"),
                        rs.getString("contract_identity"),
                        rs.getString("checkpoint"),
                        rs.getLong("claims"),
                        rs.getString("writer_id")),
                    rs.getBoolean("live"),
                    rs.getTimestamp("lease_expires_at")))
        .optional()
        .orElseThrow(() -> new GenerationException(generation + " does not exist"));
  }

  @Override
  public Claimed claim(String namespace, String generation, ExpectedManifest manifest) {
    return tx.execute(
        s -> {
          LockedRow locked = lockRow(namespace, generation);
          GenerationRow row = locked.row();
          if (row.generationStatus() != GenerationStatus.PENDING) {
            throw new GenerationException(generation + " is " + row.status() + ", not pending");
          }
          boolean mine = writerId.equals(row.writerId());
          if (locked.live() && !mine) {
            throw new FencedException(
                generation
                    + " is held by live writer "
                    + row.writerId()
                    + " (fence "
                    + row.fence()
                    + "); a live writer is never displaced");
          }
          if (!ReceiptContext.manifestSha256(manifest).equals(row.manifestSha256())) {
            throw new CheckpointException(
                "supplied manifest "
                    + ReceiptContext.manifestSha256(manifest)
                    + " is not the manifest pinned at creation "
                    + row.manifestSha256());
          }
          manifest.verifyRates(binding.rateTableSha256());
          if (row.contractIdentity() == null) {
            throw new CheckpointException(generation + " has no pinned contract identity");
          }
          if (!binding.identity().equals(row.contractIdentity())) {
            throw new CheckpointException(
                "pinned contract identity "
                    + row.contractIdentity()
                    + " differs from the running "
                    + binding.identity());
          }
          Long current =
              db.sql("SELECT current_generation_id FROM namespace WHERE name = ?")
                  .param(namespace)
                  .query(Long.class)
                  .optional()
                  .orElse(null);
          if (current == null || !current.equals(row.parentId())) {
            throw new CasException(
                "parent of " + generation + " is no longer the current generation of " + namespace);
          }
          byte[] seed = seed(row.id());
          if (!Sha256.of(seed).equals(row.seedPolinSha256())) {
            throw new CheckpointException("pinned seed bytes do not match seed_polin_sha256");
          }
          manifest.verifySeed(seed);
          PrefixVerifier.Verified verified = verifiedPrefix(row, seed);
          if (mine && locked.live()) {
            // This instance already owns the row: its earlier claim (or begin) committed but the
            // caller never saw the answer. Re-verify and hand back the same fence -- no ownership
            // change, no new fence, no claim-history row, no lease renewal (the heartbeat does
            // that).
            return new Claimed(
                new Lease(namespace, generation, parentName(row), row.fence()),
                row.claims(),
                row.lastOrdinal(),
                verified.typedRequests(),
                verified.rawRequests(),
                Sha256.of(verified.requests()),
                verified.checkpoint());
          }
          long fence = db.sql("SELECT nextval('generation_fence_seq')").query(Long.class).single();
          int moved =
              db.sql(
                      "UPDATE generation SET fence = ?, writer_id = ?,"
                          + " lease_expires_at = clock_timestamp() + ?::interval,"
                          + " claims = claims + 1"
                          + " WHERE id = ? AND status = 'PENDING' AND fence = ?"
                          + " AND last_ordinal = ? AND lease_expires_at <= clock_timestamp()")
                  .params(
                      fence,
                      writerId,
                      lease.toMillis() + " milliseconds",
                      row.id(),
                      row.fence(),
                      row.lastOrdinal())
                  .update();
          if (moved != 1) {
            throw new FencedException(generation + " changed while being claimed");
          }
          long claimNo = row.claims() + 1;
          db.sql(
                  "INSERT INTO generation_claim (generation_id, claim_no, old_fence, new_fence,"
                      + " old_writer_id, new_writer_id, old_lease_expires_at,"
                      + " verified_last_ordinal, checkpoint)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
              .params(
                  row.id(),
                  claimNo,
                  row.fence(),
                  fence,
                  row.writerId(),
                  writerId,
                  locked.leaseExpiresAt(),
                  row.lastOrdinal(),
                  verified.checkpoint())
              .update();
          return new Claimed(
              new Lease(namespace, generation, parentName(row), fence),
              claimNo,
              row.lastOrdinal(),
              verified.typedRequests(),
              verified.rawRequests(),
              Sha256.of(verified.requests()),
              verified.checkpoint());
        });
  }

  @Override
  public Optional<ExpectedManifest> manifest(String namespace, String generation) {
    return generations
        .findByNamespaceAndName(namespace, generation)
        .map(row -> manifestOf(row.id(), row.manifestSha256()));
  }

  private ExpectedManifest manifestOf(long id, String sourceSha256) {
    String json =
        db.sql("SELECT manifest FROM generation WHERE id = ?")
            .param(id)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new IntegrityException("generation " + id + " has no manifest"));
    return Json.read(json.getBytes(StandardCharsets.UTF_8), ExpectedManifest.class)
        .withSourceSha256(sourceSha256);
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
  public byte[] peekRequests(String namespace, String generation) {
    GenerationRow row = pendingRow(namespace, generation);
    List<byte[]> rows =
        db.sql("SELECT request FROM generation_entry WHERE generation_id = ? ORDER BY ordinal")
            .param(row.id())
            .query(byte[].class)
            .list();
    return concat(rows, TransactionRecord.LENGTH);
  }

  /** Append-only claim history of a generation (evidence). */
  public List<ClaimRow> claims(String namespace, String generation) {
    long id = idOf(namespace, generation);
    return db.sql(
            "SELECT claim_no, old_fence, new_fence, old_writer_id, new_writer_id,"
                + " verified_last_ordinal, checkpoint FROM generation_claim"
                + " WHERE generation_id = ? ORDER BY claim_no")
        .param(id)
        .query(ClaimRow.class)
        .list();
  }

  public record ClaimRow(
      long claimNo,
      long oldFence,
      long newFence,
      String oldWriterId,
      String newWriterId,
      long verifiedLastOrdinal,
      String checkpoint) {}

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

  /**
   * The durable prefix of a pending generation, established by re-evaluating every stored entry
   * with the running contract ({@link PrefixVerifier}); used by claim and by publication.
   */
  private PrefixVerifier.Verified verifiedPrefix(GenerationRow row, byte[] seed) {
    if (row.contractIdentity() == null || row.checkpoint() == null) {
      throw new CheckpointException(
          row.name() + " lacks its pinned contract identity or checkpoint; nothing is verifiable");
    }
    List<PrefixVerifier.Entry> entries =
        db.sql(
                "SELECT ordinal, request, result, typed, successor, chain FROM generation_entry"
                    + " WHERE generation_id = ? ORDER BY ordinal")
            .param(row.id())
            .query(
                (rs, i) ->
                    new PrefixVerifier.Entry(
                        rs.getLong("ordinal"),
                        rs.getBytes("request"),
                        rs.getBytes("result"),
                        rs.getBoolean("typed"),
                        rs.getBytes("successor"),
                        rs.getString("chain")))
            .list();
    List<byte[]> stateRows =
        db.sql("SELECT bytes FROM policy_state WHERE generation_id = ? ORDER BY position")
            .param(row.id())
            .query(byte[].class)
            .list();
    byte[] state = concat(stateRows, PolicyRecord.LENGTH);
    return PrefixVerifier.verify(
        seed,
        entries,
        row.lastOrdinal(),
        row.typedRequests(),
        row.rawRequests(),
        state,
        row.checkpoint(),
        binding);
  }

  // ---------------------------------------------------------------- helpers

  private long insertGeneration(
      String namespace,
      String generation,
      Long parentId,
      long fence,
      PolicyTable table,
      byte[] seed,
      ExpectedManifest manifest) {
    return db.sql(
            "INSERT INTO generation (namespace, name, parent_id, status, fence,"
                + " policies_count, seed_polin_sha256, seed, manifest, manifest_sha256,"
                + " writer_id, lease_expires_at, contract_identity, checkpoint)"
                + " VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?,"
                + " clock_timestamp() + ?::interval, ?, ?)"
                + " RETURNING id")
        .params(
            namespace,
            generation,
            parentId,
            fence,
            table.size(),
            Sha256.of(seed),
            seed,
            new String(Json.bytes(manifest), StandardCharsets.UTF_8),
            ReceiptContext.manifestSha256(manifest),
            writerId,
            lease.toMillis() + " milliseconds",
            binding.identity(),
            PrefixVerifier.seedChain(seed))
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

  private void markDiscarded(long id, String reason) {
    db.sql(
            "UPDATE generation SET status = 'DISCARDED', discard_reason = ?"
                + " WHERE id = ? AND status = 'PENDING'")
        .params(reason, id)
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
