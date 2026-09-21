package insurance.ledger.generation;

import insurance.contract.v001.ContractV001;
import insurance.contract.v001.Evaluation;
import insurance.contract.v001.PolicyTable;
import insurance.ledger.BuildIdentity;
import insurance.ledger.ContractBinding;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.Json;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * File-backed generation store.
 *
 * <pre>
 * root/&lt;ns&gt;/current.json                     {"current": "&lt;gen&gt;"}
 * root/&lt;ns&gt;/pending/&lt;gen&gt;/generation.json     status PENDING, fence, counters
 * root/&lt;ns&gt;/pending/&lt;gen&gt;/seed.bin            parent POLOUT bytes (128 x n)
 * root/&lt;ns&gt;/pending/&lt;gen&gt;/manifest.json       the expected manifest pinned at begin
 * root/&lt;ns&gt;/pending/&lt;gen&gt;/contract.json       contract + rate identity pinned at begin
 * root/&lt;ns&gt;/pending/&lt;gen&gt;/journal.bin         one 297-byte entry per applied request
 * root/&lt;ns&gt;/pending/&lt;gen&gt;/claims.json         ownership transitions (after a claim)
 * root/&lt;ns&gt;/published/&lt;gen&gt;/... + state.bin, results.bin, requests.bin, receipt.json
 * root/&lt;ns&gt;/discarded/&lt;gen&gt;/...
 * </pre>
 *
 * <p>Each applied request is one appended journal entry (request 40, result 96, typed flag 1,
 * successor master 128 or zeros, hash chain 32) written with {@code fsync}. The journal is the
 * checkpoint: the ordinal is the entry index and the chain binds every entry to all previous ones
 * and to the seed. {@code generation.json} is a summary rewritten after the append; a crash between
 * the two leaves it one entry behind, which a claim detects and re-derives from the journal (the
 * journal is never edited). A journal whose length is not a whole number of entries is a torn tail
 * and fails closed. Publication is two distinct operations: {@code rename(pending/gen,
 * published/gen)} and then replacement of {@code current.json} via a temporary file and {@code
 * rename(2)}. They are not jointly atomic: a crash between them leaves an orphan published
 * directory that is not reachable from {@code current.json} and is therefore never served.
 *
 * <p>Ownership: {@link #open} takes an exclusive OS lock on {@code root/.store.lock} for the
 * lifetime of the store and refuses to open while another process holds it, so a live writer of a
 * pending directory is always in this process. A pending directory found on open belongs to a
 * writer that no longer exists ({@link #abandonedOnOpen}); it is left in place, unserved, until it
 * is {@link #claim claimed} -- pinned manifest, rate table and contract identity checked and the
 * whole journal re-evaluated by the running contract ({@link PrefixVerifier}) before a new fence is
 * issued -- or explicitly {@link #discardAbandoned discarded}. Within the process one fence is live
 * per pending generation; an in-process holder is never displaced by a claim. Files are fsynced,
 * but no power-loss durability is claimed: no cut-power test has been run.
 */
public final class FileGenerationStore implements GenerationStore {
  static final int CHAIN = 32;
  static final int ENTRY =
      TransactionRecord.LENGTH + ResultRecord.LENGTH + 1 + PolicyRecord.LENGTH + CHAIN;

  public static final class IntegrityException extends GenerationException {
    private static final long serialVersionUID = 1L;

    public IntegrityException(String message) {
      super(message);
    }
  }

  /** Another process holds the store lock; nothing was read or recovered. */
  public static final class StoreLockedException extends GenerationException {
    private static final long serialVersionUID = 1L;

    public StoreLockedException(String message) {
      super(message);
    }
  }

  public static final String LOCK_FILE = ".store.lock";

  /**
   * Test hook: when this system property is {@code true} the JVM halts between the two publication
   * renames (directory moved to {@code published/}, {@code current.json} not yet replaced).
   */
  public static final String HALT_BETWEEN_RENAMES = "insurance.store.halt-between-renames";

  /**
   * Store roots locked by this JVM. POSIX record locks are per process and closing any channel on
   * the lock file drops the process's lock, so a second in-process open must be refused before it
   * opens (and later closes) a channel of its own.
   */
  private static final Set<Path> HELD_IN_JVM = new HashSet<>();

  private final Path root;
  private final Object lock = new Object();
  private final ContractBinding binding;
  private FileChannel lockChannel;
  private FileLock osLock;
  private final Map<String, PolicyTable> tables = new HashMap<>();
  private final Map<String, GenerationInfo> pendingInfo = new HashMap<>();
  private final Map<String, String> checkpoints = new HashMap<>();
  private long nextFence = System.nanoTime();
  private final List<String> abandonedOnOpen = new ArrayList<>();

  private FileGenerationStore(Path root, ContractBinding binding) {
    this.root = root;
    this.binding = binding;
  }

  /**
   * Opens (creating if needed) and verifies the store. The OS lock is taken before anything is
   * read; a second process gets {@link StoreLockedException}. Pending directories are inventoried
   * (never moved) only once the lock is held.
   */
  public static FileGenerationStore open(Path root) {
    return open(root, ContractBinding.of(ContractV001.frozen()));
  }

  public static FileGenerationStore open(Path root, ContractBinding binding) {
    FileGenerationStore store = new FileGenerationStore(root, binding);
    try {
      Files.createDirectories(root);
      store.acquireOsLock();
      try {
        for (String ns : store.namespaces()) {
          store.recover(ns);
        }
      } catch (RuntimeException e) {
        store.close();
        throw e;
      }
    } catch (IOException e) {
      store.close();
      throw new UncheckedIOException(e);
    }
    return store;
  }

  private void acquireOsLock() throws IOException {
    Path lockPath = root.resolve(LOCK_FILE).toAbsolutePath().normalize();
    synchronized (HELD_IN_JVM) {
      if (HELD_IN_JVM.contains(lockPath)) {
        throw new StoreLockedException(
            "store " + root + " is already open in this process (" + LOCK_FILE + ")");
      }
      FileChannel channel =
          FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock acquired;
      try {
        acquired = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        acquired = null;
      }
      if (acquired == null) {
        channel.close();
        throw new StoreLockedException(
            "store " + root + " is locked by another process (" + LOCK_FILE + ")");
      }
      HELD_IN_JVM.add(lockPath);
      lockChannel = channel;
      osLock = acquired;
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      try {
        if (osLock != null && osLock.isValid()) {
          osLock.release();
        }
        if (lockChannel != null) {
          lockChannel.close();
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } finally {
        if (osLock != null) {
          synchronized (HELD_IN_JVM) {
            HELD_IN_JVM.remove(root.resolve(LOCK_FILE).toAbsolutePath().normalize());
          }
        }
        osLock = null;
        lockChannel = null;
        tables.clear();
        pendingInfo.clear();
        checkpoints.clear();
      }
    }
  }

  private void requireOpen() {
    if (osLock == null) {
      throw new GenerationException("store " + root + " is closed");
    }
  }

  /** Pending generations found on open whose writer no longer exists: claimable or discardable. */
  public List<String> abandonedOnOpen() {
    return List.copyOf(abandonedOnOpen);
  }

  public ContractBinding binding() {
    return binding;
  }

  private void recover(String ns) throws IOException {
    Path pending = root.resolve(ns).resolve("pending");
    if (Files.isDirectory(pending)) {
      for (Path gen : list(pending)) {
        abandonedOnOpen.add(ns + "/" + gen.getFileName());
      }
    }
    verifyAncestry(ns);
  }

  /** Walks current -> parent -> root, verifying each published generation against its receipt. */
  private Set<String> verifyAncestry(String ns) {
    Set<String> reachable = new HashSet<>();
    Optional<String> cur = current(ns);
    String gen = cur.orElse(null);
    while (gen != null) {
      Path dir = root.resolve(ns).resolve("published").resolve(gen);
      if (!Files.isDirectory(dir)) {
        throw new IntegrityException(ns + ": current ancestry references missing " + gen);
      }
      GenerationInfo info = Json.read(dir.resolve("generation.json"), GenerationInfo.class);
      if (info.status() != GenerationStatus.PUBLISHED) {
        throw new IntegrityException(ns + "/" + gen + ": status " + info.status());
      }
      Receipt receipt = Json.read(dir.resolve("receipt.json"), Receipt.class);
      byte[] state = read(dir.resolve("state.bin"));
      byte[] results = read(dir.resolve("results.bin"));
      byte[] requests = read(dir.resolve("requests.bin"));
      if (!receipt.poloutSha256().equals(Sha256.of(state))
          || !receipt.resoutSha256().equals(Sha256.of(results))
          || !receipt.txninSha256().equals(Sha256.of(requests))) {
        throw new IntegrityException(ns + "/" + gen + ": receipt hashes do not match files");
      }
      if (results.length / ResultRecord.LENGTH != info.lastOrdinal()) {
        throw new IntegrityException(ns + "/" + gen + ": result count != last ordinal");
      }
      if (!reachable.add(gen)) {
        throw new IntegrityException(ns + ": ancestry cycle at " + gen);
      }
      gen = info.parent();
    }
    return reachable;
  }

  @Override
  public GenerationInfo bootstrap(
      String namespace, String generation, List<PolicyRecord> masters, ExpectedManifest manifest) {
    synchronized (lock) {
      requireOpen();
      validateName(namespace);
      validateName(generation);
      if (current(namespace).isPresent()) {
        throw new CasException(namespace + " already has a current generation");
      }
      PolicyTable table = PolicyTable.load(masters);
      byte[] seed = table.size() == 0 ? new byte[0] : Records.join(table.rows());
      Path dir = root.resolve(namespace).resolve("published").resolve(generation);
      if (Files.exists(dir) || Files.exists(pendingDir(namespace, generation))) {
        throw new GenerationException(generation + " already exists");
      }
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
      manifest.verifyInputs(seed, new byte[0]);
      try {
        Files.createDirectories(dir);
        write(dir.resolve("seed.bin"), seed);
        write(dir.resolve("manifest.json"), Json.bytes(manifest));
        write(dir.resolve("journal.bin"), new byte[0]);
        write(dir.resolve("state.bin"), seed);
        write(dir.resolve("results.bin"), new byte[0]);
        write(dir.resolve("requests.bin"), new byte[0]);
        Receipt receipt =
            new ReceiptContext("bootstrap", "n/a", "n/a", manifest.ratesSha256())
                .complete(
                    info, manifest, seed, new byte[0], seed, new byte[0], BuildIdentity.runtime());
        write(dir.resolve("receipt.json"), Json.bytes(receipt));
        write(dir.resolve("generation.json"), Json.bytes(info));
        setCurrent(namespace, generation);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return info;
    }
  }

  @Override
  public Lease begin(
      String namespace, String parent, String generation, ExpectedManifest manifest) {
    synchronized (lock) {
      requireOpen();
      validateName(generation);
      Set<String> reachable = verifyAncestry(namespace);
      if (!reachable.contains(parent)) {
        throw new GenerationException(parent + " is not a published generation of " + namespace);
      }
      Path pdir = pendingDir(namespace, generation);
      if (Files.exists(pdir) || Files.exists(publishedDir(namespace, generation))) {
        throw new GenerationException(generation + " already exists");
      }
      byte[] seed = read(publishedDir(namespace, parent).resolve("state.bin"));
      manifest.verifySeed(seed);
      PolicyTable table = PolicyTable.load(Records.policies(seed));
      long fence = ++nextFence;
      GenerationInfo info =
          new GenerationInfo(
              namespace,
              generation,
              parent,
              GenerationStatus.PENDING,
              fence,
              table.size(),
              0,
              0,
              0,
              Sha256.of(seed),
              ReceiptContext.manifestSha256(manifest));
      try {
        Files.createDirectories(pdir);
        write(pdir.resolve("seed.bin"), seed);
        write(pdir.resolve("manifest.json"), Json.bytes(manifest));
        write(
            pdir.resolve("contract.json"),
            Json.bytes(new ContractPin(binding.identity(), binding.rateTableSha256())));
        write(pdir.resolve("journal.bin"), new byte[0]);
        write(pdir.resolve("generation.json"), Json.bytes(info));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      tables.put(key(namespace, generation), table);
      pendingInfo.put(key(namespace, generation), info);
      checkpoints.put(key(namespace, generation), PrefixVerifier.seedChain(seed));
      return new Lease(namespace, generation, parent, fence);
    }
  }

  @Override
  public Optional<PolicyRecord> policy(String namespace, String generation, byte[] id) {
    synchronized (lock) {
      PolicyTable table = tables.get(key(namespace, generation));
      if (table != null) {
        return table.find(id);
      }
      if (!verifyAncestry(namespace).contains(generation)) {
        throw new GenerationException(
            generation + " is not a published generation of " + namespace);
      }
      byte[] state = read(publishedDir(namespace, generation).resolve("state.bin"));
      return PolicyTable.ofValidated(Records.policies(state)).find(id);
    }
  }

  @Override
  public Applied commit(
      Lease lease,
      TransactionRecord request,
      Function<Optional<PolicyRecord>, Evaluation> evaluator,
      boolean typed) {
    synchronized (lock) {
      GenerationInfo info = checkedPending(lease);
      String k = key(lease.namespace(), lease.generation());
      PolicyTable table = tables.get(k);
      Evaluation evaluation = evaluator.apply(table.find(request.id()));
      byte[] successor = evaluation.accepted() ? evaluation.master().bytes() : null;
      long ordinal = info.lastOrdinal() + 1;
      String chain =
          PrefixVerifier.chain(
              checkpoints.get(k),
              ordinal,
              request.bytes(),
              evaluation.result().bytes(),
              typed,
              successor);
      byte[] entry = new byte[ENTRY];
      System.arraycopy(request.bytes(), 0, entry, 0, TransactionRecord.LENGTH);
      System.arraycopy(
          evaluation.result().bytes(), 0, entry, TransactionRecord.LENGTH, ResultRecord.LENGTH);
      entry[TransactionRecord.LENGTH + ResultRecord.LENGTH] = (byte) (typed ? 1 : 0);
      if (successor != null) {
        System.arraycopy(
            successor, 0, entry, ENTRY - CHAIN - PolicyRecord.LENGTH, PolicyRecord.LENGTH);
      }
      System.arraycopy(Hex.parse(chain), 0, entry, ENTRY - CHAIN, CHAIN);
      Path pdir = pendingDir(lease.namespace(), lease.generation());
      try {
        append(pdir.resolve("journal.bin"), entry);
        checkpoints.put(k, chain);
        GenerationInfo next = info.advanced(typed);
        write(pdir.resolve("generation.json"), Json.bytes(next));
        pendingInfo.put(k, next);
        if (evaluation.accepted()) {
          table.replace(evaluation.master());
        }
        return new Applied(next.lastOrdinal(), evaluation);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  @Override
  public Receipt publish(Lease lease, ReceiptContext context) {
    synchronized (lock) {
      GenerationInfo info = checkedPending(lease);
      Path pdir = pendingDir(lease.namespace(), lease.generation());
      try {
        ExpectedManifest manifest = readManifest(pdir, info.expectedManifestSha256());
        Optional<String> cur = current(lease.namespace());
        if (!cur.map(lease.parent()::equals).orElse(false)) {
          throw new CasException(
              "current generation is " + cur.orElse("<none>") + ", expected " + lease.parent());
        }
        byte[] seed = read(pdir.resolve("seed.bin"));
        byte[] journal = read(pdir.resolve("journal.bin"));
        PolicyTable table = tables.get(key(lease.namespace(), lease.generation()));
        byte[] state = table.size() == 0 ? new byte[0] : Records.join(table.rows());
        PrefixVerifier.Verified r =
            PrefixVerifier.verify(
                seed,
                entries(journal, lease.generation()),
                info.lastOrdinal(),
                info.typedRequests(),
                info.rawRequests(),
                state,
                checkpoints.get(key(lease.namespace(), lease.generation())),
                binding);
        context.validatePublication(manifest, seed, r.requests(), r.state(), r.results());
        GenerationInfo published = info.with(GenerationStatus.PUBLISHED);
        Receipt receipt =
            context.complete(
                published,
                manifest,
                seed,
                r.requests(),
                r.state(),
                r.results(),
                BuildIdentity.runtime());
        write(pdir.resolve("state.bin"), r.state());
        write(pdir.resolve("results.bin"), r.results());
        write(pdir.resolve("requests.bin"), r.requests());
        write(pdir.resolve("receipt.json"), Json.bytes(receipt));
        write(pdir.resolve("generation.json"), Json.bytes(published));
        Path target = publishedDir(lease.namespace(), lease.generation());
        Files.createDirectories(target.getParent());
        Files.move(pdir, target, StandardCopyOption.ATOMIC_MOVE);
        if (Boolean.getBoolean(HALT_BETWEEN_RENAMES)) {
          Runtime.getRuntime().halt(137);
        }
        setCurrent(lease.namespace(), lease.generation());
        forget(key(lease.namespace(), lease.generation()));
        return receipt;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (FencedException e) {
        throw e;
      } catch (GenerationException | ExpectedManifest.ManifestException e) {
        discardQuietly(lease);
        throw e;
      }
    }
  }

  @Override
  public Optional<ExpectedManifest> manifest(String namespace, String generation) {
    synchronized (lock) {
      for (String kind : List.of("pending", "published", "discarded")) {
        Path dir = root.resolve(namespace).resolve(kind).resolve(generation);
        if (Files.isRegularFile(dir.resolve("manifest.json"))) {
          GenerationInfo info = Json.read(dir.resolve("generation.json"), GenerationInfo.class);
          return Optional.of(readManifest(dir, info.expectedManifestSha256()));
        }
      }
      return Optional.empty();
    }
  }

  private static ExpectedManifest readManifest(Path dir, String sourceSha256) {
    return Json.read(dir.resolve("manifest.json"), ExpectedManifest.class)
        .withSourceSha256(sourceSha256);
  }

  @Override
  public void discard(Lease lease) {
    synchronized (lock) {
      checkedPending(lease);
      discardQuietly(lease);
    }
  }

  private void discardQuietly(Lease lease) {
    moveToDiscarded(lease.namespace(), lease.generation());
  }

  @Override
  public void discardAbandoned(String namespace, String generation, String reason) {
    synchronized (lock) {
      requireOpen();
      if (pendingInfo.containsKey(key(namespace, generation))) {
        throw new FencedException(generation + " is held by a live writer in this process");
      }
      if (!Files.isDirectory(pendingDir(namespace, generation))) {
        throw new GenerationException(generation + " is not a pending generation of " + namespace);
      }
      moveToDiscarded(namespace, generation);
    }
  }

  private void moveToDiscarded(String namespace, String generation) {
    Path pdir = pendingDir(namespace, generation);
    if (!Files.isDirectory(pdir)) {
      return;
    }
    try {
      GenerationInfo info =
          pendingInfo
              .getOrDefault(
                  key(namespace, generation),
                  Json.read(pdir.resolve("generation.json"), GenerationInfo.class))
              .with(GenerationStatus.DISCARDED);
      write(pdir.resolve("generation.json"), Json.bytes(info));
      Path target = root.resolve(namespace).resolve("discarded").resolve(generation);
      Files.createDirectories(target.getParent());
      Files.move(pdir, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      forget(key(namespace, generation));
    }
  }

  private void forget(String k) {
    tables.remove(k);
    pendingInfo.remove(k);
    checkpoints.remove(k);
  }

  record ContractPin(String identity, String ratesSha256) {}

  record ClaimRecord(
      long claimNo,
      long oldFence,
      long newFence,
      long verifiedLastOrdinal,
      String checkpoint,
      String claimant) {}

  /** Claim history of a pending or published generation (evidence). */
  public List<ClaimRecord> claims(String namespace, String generation) {
    synchronized (lock) {
      for (String kind : List.of("pending", "published", "discarded")) {
        Path file =
            root.resolve(namespace).resolve(kind).resolve(generation).resolve("claims.json");
        if (Files.isRegularFile(file)) {
          return List.of(Json.read(file, ClaimRecord[].class));
        }
      }
      return List.of();
    }
  }

  @Override
  public Claimed claim(String namespace, String generation, ExpectedManifest manifest) {
    synchronized (lock) {
      requireOpen();
      String k = key(namespace, generation);
      Path pdir = pendingDir(namespace, generation);
      if (pendingInfo.containsKey(k)) {
        // This process already holds the generation (its claim or begin completed): hand back the
        // same fence after re-verifying the journal. The file store has one writer per process and
        // no lease, so the only "other" writer is another process, which never gets this far.
        GenerationInfo held = pendingInfo.get(k);
        if (!ReceiptContext.manifestSha256(manifest).equals(held.expectedManifestSha256())) {
          throw new CheckpointException(
              "supplied manifest "
                  + ReceiptContext.manifestSha256(manifest)
                  + " is not the manifest pinned at creation "
                  + held.expectedManifestSha256());
        }
        byte[] seed = read(pdir.resolve("seed.bin"));
        List<PrefixVerifier.Entry> entries = entries(read(pdir.resolve("journal.bin")), generation);
        PrefixVerifier.Verified verified =
            PrefixVerifier.verify(
                seed,
                entries,
                held.lastOrdinal(),
                held.typedRequests(),
                held.rawRequests(),
                null,
                checkpoints.get(k),
                binding);
        return new Claimed(
            new Lease(namespace, generation, held.parent(), held.fence()),
            claims(namespace, generation).size(),
            held.lastOrdinal(),
            verified.typedRequests(),
            verified.rawRequests(),
            Sha256.of(verified.requests()),
            verified.checkpoint());
      }
      if (!Files.isDirectory(pdir)) {
        Optional<GenerationInfo> other = info(namespace, generation);
        throw new GenerationException(
            generation
                + " is "
                + other.map(i -> i.status().name()).orElse("unknown")
                + ", not an abandoned pending generation of "
                + namespace);
      }
      GenerationInfo stored = Json.read(pdir.resolve("generation.json"), GenerationInfo.class);
      if (stored.status() != GenerationStatus.PENDING) {
        throw new GenerationException(generation + " is " + stored.status());
      }
      String pinnedManifest = stored.expectedManifestSha256();
      if (!ReceiptContext.manifestSha256(manifest).equals(pinnedManifest)) {
        throw new CheckpointException(
            "supplied manifest "
                + ReceiptContext.manifestSha256(manifest)
                + " is not the manifest pinned at creation "
                + pinnedManifest);
      }
      readManifest(pdir, pinnedManifest);
      manifest.verifyRates(binding.rateTableSha256());
      Path pin = pdir.resolve("contract.json");
      if (!Files.isRegularFile(pin)) {
        throw new CheckpointException(generation + " has no pinned contract identity");
      }
      ContractPin pinned = Json.read(pin, ContractPin.class);
      if (!binding.identity().equals(pinned.identity())) {
        throw new CheckpointException(
            "pinned contract identity "
                + pinned.identity()
                + " differs from the running "
                + binding.identity());
      }
      Optional<String> cur = current(namespace);
      if (!cur.map(c -> c.equals(stored.parent())).orElse(false)) {
        throw new CasException(
            "parent " + stored.parent() + " is no longer the current generation of " + namespace);
      }
      byte[] seed = read(pdir.resolve("seed.bin"));
      if (!Sha256.of(seed).equals(stored.seedPolinSha256())) {
        throw new CheckpointException("pinned seed bytes do not match seed_polin_sha256");
      }
      manifest.verifySeed(seed);
      byte[] journal = read(pdir.resolve("journal.bin"));
      List<PrefixVerifier.Entry> entries = entries(journal, generation);
      long durable = entries.size();
      if (durable != stored.lastOrdinal() && durable != stored.lastOrdinal() + 1) {
        throw new CheckpointException(
            "journal has "
                + durable
                + " entries but the summary says "
                + stored.lastOrdinal()
                + " (more than the single append the summary can lag)");
      }
      // The summary may lag the journal by the one entry whose generation.json write was lost, so
      // its counters must account for exactly the entries it claims to describe.
      int summarised = (int) stored.lastOrdinal();
      int typedInSummary = 0;
      for (int i = 0; i < summarised; i++) {
        if (entries.get(i).typed()) {
          typedInSummary++;
        }
      }
      if (stored.typedRequests() != typedInSummary
          || stored.rawRequests() != summarised - typedInSummary) {
        throw new CheckpointException(
            "summary counts "
                + stored.typedRequests()
                + " typed / "
                + stored.rawRequests()
                + " raw requests but the journal carries "
                + typedInSummary
                + " / "
                + (summarised - typedInSummary));
      }
      int typed =
          typedInSummary + (durable > summarised && entries.get(summarised).typed() ? 1 : 0);
      int raw = (int) durable - typed;
      String lastChain =
          entries.isEmpty()
              ? PrefixVerifier.seedChain(seed)
              : entries.get(entries.size() - 1).chain();
      PrefixVerifier.Verified verified =
          PrefixVerifier.verify(seed, entries, durable, typed, raw, null, lastChain, binding);
      long fence = Math.max(stored.fence(), ++nextFence) + 1;
      nextFence = fence;
      GenerationInfo claimed =
          new GenerationInfo(
              namespace,
              generation,
              stored.parent(),
              GenerationStatus.PENDING,
              fence,
              stored.policiesCount(),
              durable,
              typed,
              raw,
              stored.seedPolinSha256(),
              stored.expectedManifestSha256());
      List<ClaimRecord> history = new ArrayList<>(claims(namespace, generation));
      history.add(
          new ClaimRecord(
              history.size() + 1,
              stored.fence(),
              fence,
              durable,
              verified.checkpoint(),
              ProcessHandle.current().pid() + "@" + Thread.currentThread().getName()));
      try {
        write(pdir.resolve("claims.json"), Json.bytes(history));
        write(pdir.resolve("generation.json"), Json.bytes(claimed));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      tables.put(k, PolicyTable.ofValidated(Records.policies(verified.state())));
      pendingInfo.put(k, claimed);
      checkpoints.put(k, verified.checkpoint());
      return new Claimed(
          new Lease(namespace, generation, stored.parent(), fence),
          history.size(),
          durable,
          typed,
          raw,
          Sha256.of(verified.requests()),
          verified.checkpoint());
    }
  }

  @Override
  public Optional<String> current(String namespace) {
    Path file = root.resolve(namespace).resolve("current.json");
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    return Optional.ofNullable(Json.read(file, CurrentPointer.class).current());
  }

  @Override
  public Optional<GenerationInfo> info(String namespace, String generation) {
    synchronized (lock) {
      GenerationInfo pending = pendingInfo.get(key(namespace, generation));
      if (pending != null) {
        return Optional.of(pending);
      }
      Path abandoned = pendingDir(namespace, generation).resolve("generation.json");
      if (Files.isRegularFile(abandoned)) {
        // a pending generation with no live writer in this process: claimable or discardable
        return Optional.of(Json.read(abandoned, GenerationInfo.class));
      }
      for (String kind : List.of("published", "discarded")) {
        Path file =
            root.resolve(namespace).resolve(kind).resolve(generation).resolve("generation.json");
        if (Files.isRegularFile(file)) {
          GenerationInfo info = Json.read(file, GenerationInfo.class);
          if (kind.equals("published") && verifyAncestry(namespace).contains(generation)) {
            return Optional.of(info);
          }
          // orphan published directory, or a pending directory moved by recovery
          return Optional.of(info.with(GenerationStatus.DISCARDED));
        }
      }
      return Optional.empty();
    }
  }

  @Override
  public byte[] polout(String namespace, String generation) {
    return publishedFile(namespace, generation, "state.bin");
  }

  @Override
  public byte[] resout(String namespace, String generation) {
    return publishedFile(namespace, generation, "results.bin");
  }

  @Override
  public byte[] requests(String namespace, String generation) {
    return publishedFile(namespace, generation, "requests.bin");
  }

  @Override
  public Optional<Receipt> receipt(String namespace, String generation) {
    synchronized (lock) {
      if (!verifyAncestry(namespace).contains(generation)) {
        return Optional.empty();
      }
      return Optional.of(
          Json.read(publishedDir(namespace, generation).resolve("receipt.json"), Receipt.class));
    }
  }

  @Override
  public byte[] peekPolout(String namespace, String generation) {
    synchronized (lock) {
      PolicyTable table = tables.get(key(namespace, generation));
      if (table == null) {
        throw new GenerationException(generation + " is not an open pending generation");
      }
      return table.size() == 0 ? new byte[0] : Records.join(table.rows());
    }
  }

  @Override
  public byte[] peekResout(String namespace, String generation) {
    return peekColumn(namespace, generation, TransactionRecord.LENGTH, ResultRecord.LENGTH);
  }

  @Override
  public byte[] peekRequests(String namespace, String generation) {
    return peekColumn(namespace, generation, 0, TransactionRecord.LENGTH);
  }

  private byte[] peekColumn(String namespace, String generation, int offset, int length) {
    synchronized (lock) {
      Path pdir = pendingDir(namespace, generation);
      if (!tables.containsKey(key(namespace, generation))) {
        throw new GenerationException(generation + " is not an open pending generation");
      }
      byte[] journal = read(pdir.resolve("journal.bin"));
      int n = journal.length / ENTRY;
      byte[] out = new byte[n * length];
      for (int i = 0; i < n; i++) {
        System.arraycopy(journal, i * ENTRY + offset, out, i * length, length);
      }
      return out;
    }
  }

  @Override
  public List<String> namespaces() {
    List<String> names = new ArrayList<>();
    try {
      for (Path p : list(root)) {
        if (Files.isDirectory(p)) {
          names.add(p.getFileName().toString());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return names;
  }

  private byte[] publishedFile(String namespace, String generation, String name) {
    synchronized (lock) {
      if (!verifyAncestry(namespace).contains(generation)) {
        throw new GenerationException(
            generation + " is not a published generation reachable from current of " + namespace);
      }
      return read(publishedDir(namespace, generation).resolve(name));
    }
  }

  private GenerationInfo checkedPending(Lease lease) {
    GenerationInfo info = pendingInfo.get(key(lease.namespace(), lease.generation()));
    if (info == null) {
      throw new FencedException(lease.generation() + " is not an open pending generation");
    }
    if (info.fence() != lease.fence()) {
      throw new FencedException("stale fence for " + lease.generation());
    }
    return info;
  }

  /** Decodes the journal into verifier entries; a length that is not whole entries fails closed. */
  private static List<PrefixVerifier.Entry> entries(byte[] journal, String generation) {
    if (journal.length % ENTRY != 0) {
      throw new CheckpointException(
          "torn journal tail in " + generation + ": " + (journal.length % ENTRY) + " stray bytes");
    }
    int n = journal.length / ENTRY;
    List<PrefixVerifier.Entry> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      int base = i * ENTRY;
      byte[] request = java.util.Arrays.copyOfRange(journal, base, base + TransactionRecord.LENGTH);
      byte[] result =
          java.util.Arrays.copyOfRange(
              journal,
              base + TransactionRecord.LENGTH,
              base + TransactionRecord.LENGTH + ResultRecord.LENGTH);
      byte flag = journal[base + TransactionRecord.LENGTH + ResultRecord.LENGTH];
      if (flag != 0 && flag != 1) {
        throw new CheckpointException(
            "entry " + (i + 1) + " of " + generation + " has an invalid typed flag " + flag);
      }
      byte[] successor =
          java.util.Arrays.copyOfRange(
              journal, base + ENTRY - CHAIN - PolicyRecord.LENGTH, base + ENTRY - CHAIN);
      boolean zeros = true;
      for (byte b : successor) {
        zeros &= b == 0;
      }
      String chain =
          Hex.of(java.util.Arrays.copyOfRange(journal, base + ENTRY - CHAIN, base + ENTRY));
      out.add(
          new PrefixVerifier.Entry(
              i + 1, request, result, flag == 1, zeros ? null : successor, chain));
    }
    return out;
  }

  private record CurrentPointer(
      @com.fasterxml.jackson.annotation.JsonProperty("current") String current) {}

  private void setCurrent(String namespace, String generation) throws IOException {
    Path dir = root.resolve(namespace);
    Files.createDirectories(dir);
    Path tmp = dir.resolve("current.json.tmp");
    write(tmp, Json.bytes(new CurrentPointer(generation)));
    Files.move(
        tmp,
        dir.resolve("current.json"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  private Path pendingDir(String namespace, String generation) {
    return root.resolve(namespace).resolve("pending").resolve(generation);
  }

  private Path publishedDir(String namespace, String generation) {
    return root.resolve(namespace).resolve("published").resolve(generation);
  }

  private static String key(String namespace, String generation) {
    return namespace + "/" + generation;
  }

  private static void validateName(String name) {
    if (name == null || name.isEmpty() || !name.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new GenerationException("invalid namespace/generation name: " + name);
    }
  }

  private static List<Path> list(Path dir) throws IOException {
    TreeSet<Path> sorted = new TreeSet<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path p : stream) {
        sorted.add(p);
      }
    }
    return new ArrayList<>(sorted);
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(Path path, byte[] data) throws IOException {
    try (FileChannel ch =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer buf = ByteBuffer.wrap(data);
      while (buf.hasRemaining()) {
        ch.write(buf);
      }
      ch.force(true);
    }
  }

  private static void append(Path path, byte[] data) throws IOException {
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.APPEND)) {
      ByteBuffer buf = ByteBuffer.wrap(data);
      while (buf.hasRemaining()) {
        ch.write(buf);
      }
      ch.force(true);
    }
  }
}
