package insurance.ledger.generation;

import insurance.contract.v001.Evaluation;
import insurance.contract.v001.PolicyTable;
import insurance.contract.v001.Status;
import insurance.ledger.BuildIdentity;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.Json;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.ResultRecord;
import insurance.legacy.codec.TransactionRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
 * root/&lt;ns&gt;/pending/&lt;gen&gt;/journal.bin         one 265-byte entry per applied request
 * root/&lt;ns&gt;/published/&lt;gen&gt;/... + state.bin, results.bin, requests.bin, receipt.json
 * root/&lt;ns&gt;/discarded/&lt;gen&gt;/...
 * </pre>
 *
 * <p>Each applied request is one appended journal entry (request 40, result 96, typed flag 1,
 * successor master 128 or zeros), so the ordinal is the entry index and a torn tail is detectable.
 * Publication is two distinct operations: {@code rename(pending/gen, published/gen)} and then
 * replacement of {@code current.json} via a temporary file and {@code rename(2)}. They are not
 * jointly atomic: a crash between them leaves an orphan published directory that is not reachable
 * from {@code current.json} and is therefore never served. Fencing is in-process (one JVM, one
 * writer per pending generation); on open every pending generation is discarded (fail closed).
 * Files are fsynced, but no power-loss durability is claimed: no cut-power test has been run.
 */
public final class FileGenerationStore implements GenerationStore {
  private static final int ENTRY =
      TransactionRecord.LENGTH + ResultRecord.LENGTH + 1 + PolicyRecord.LENGTH;

  public static final class IntegrityException extends GenerationException {
    private static final long serialVersionUID = 1L;

    public IntegrityException(String message) {
      super(message);
    }
  }

  private final Path root;
  private final Object lock = new Object();
  private final Map<String, PolicyTable> tables = new HashMap<>();
  private final Map<String, GenerationInfo> pendingInfo = new HashMap<>();
  private long nextFence = System.nanoTime();
  private final List<String> discardedOnOpen = new ArrayList<>();

  private FileGenerationStore(Path root) {
    this.root = root;
  }

  /** Opens (creating if needed) and verifies the store; stale pending generations are discarded. */
  public static FileGenerationStore open(Path root) {
    FileGenerationStore store = new FileGenerationStore(root);
    try {
      Files.createDirectories(root);
      for (String ns : store.namespaces()) {
        store.recover(ns);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return store;
  }

  public List<String> discardedOnOpen() {
    return List.copyOf(discardedOnOpen);
  }

  private void recover(String ns) throws IOException {
    Path pending = root.resolve(ns).resolve("pending");
    if (Files.isDirectory(pending)) {
      for (Path gen : list(pending)) {
        Path target = root.resolve(ns).resolve("discarded").resolve(gen.getFileName());
        Files.createDirectories(target.getParent());
        Files.move(gen, target, StandardCopyOption.ATOMIC_MOVE);
        discardedOnOpen.add(ns + "/" + gen.getFileName());
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
  public GenerationInfo bootstrap(String namespace, String generation, List<PolicyRecord> masters) {
    synchronized (lock) {
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
              Sha256.of(seed));
      try {
        Files.createDirectories(dir);
        write(dir.resolve("seed.bin"), seed);
        write(dir.resolve("journal.bin"), new byte[0]);
        write(dir.resolve("state.bin"), seed);
        write(dir.resolve("results.bin"), new byte[0]);
        write(dir.resolve("requests.bin"), new byte[0]);
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
        Receipt receipt =
            new ReceiptContext("bootstrap", "n/a", "n/a", "n/a")
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
  public Lease begin(String namespace, String parent, String generation) {
    synchronized (lock) {
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
              Sha256.of(seed));
      try {
        Files.createDirectories(pdir);
        write(pdir.resolve("seed.bin"), seed);
        write(pdir.resolve("journal.bin"), new byte[0]);
        write(pdir.resolve("generation.json"), Json.bytes(info));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      tables.put(key(namespace, generation), table);
      pendingInfo.put(key(namespace, generation), info);
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
      PolicyTable table = tables.get(key(lease.namespace(), lease.generation()));
      Evaluation evaluation = evaluator.apply(table.find(request.id()));
      byte[] entry = new byte[ENTRY];
      System.arraycopy(request.bytes(), 0, entry, 0, TransactionRecord.LENGTH);
      System.arraycopy(
          evaluation.result().bytes(), 0, entry, TransactionRecord.LENGTH, ResultRecord.LENGTH);
      entry[TransactionRecord.LENGTH + ResultRecord.LENGTH] = (byte) (typed ? 1 : 0);
      if (evaluation.accepted()) {
        System.arraycopy(
            evaluation.master().bytes(),
            0,
            entry,
            ENTRY - PolicyRecord.LENGTH,
            PolicyRecord.LENGTH);
      }
      Path pdir = pendingDir(lease.namespace(), lease.generation());
      try {
        append(pdir.resolve("journal.bin"), entry);
        GenerationInfo next = info.advanced(typed);
        write(pdir.resolve("generation.json"), Json.bytes(next));
        pendingInfo.put(key(lease.namespace(), lease.generation()), next);
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
  public Receipt publish(Lease lease, ExpectedManifest manifest, ReceiptContext context) {
    synchronized (lock) {
      GenerationInfo info = checkedPending(lease);
      Path pdir = pendingDir(lease.namespace(), lease.generation());
      try {
        Optional<String> cur = current(lease.namespace());
        if (!cur.map(lease.parent()::equals).orElse(false)) {
          throw new CasException(
              "current generation is " + cur.orElse("<none>") + ", expected " + lease.parent());
        }
        byte[] seed = read(pdir.resolve("seed.bin"));
        byte[] journal = read(pdir.resolve("journal.bin"));
        if (journal.length % ENTRY != 0) {
          throw new IntegrityException("torn journal entry in " + lease.generation());
        }
        long entries = journal.length / ENTRY;
        if (entries != info.lastOrdinal()) {
          throw new IntegrityException(
              "journal has " + entries + " entries but last ordinal is " + info.lastOrdinal());
        }
        Rebuilt r = rebuild(seed, journal);
        if (!manifest.polinSha256().equals(Sha256.of(seed))) {
          throw new GenerationException("pinned POLIN hash does not match the seeded parent bytes");
        }
        if (!manifest.txninSha256().equals(Sha256.of(r.requests))) {
          throw new GenerationException("pinned TXNIN hash does not match the applied requests");
        }
        manifest.verifyOutputs(r.state, r.results, 0);
        GenerationInfo published = info.with(GenerationStatus.PUBLISHED);
        Receipt receipt =
            context.complete(
                published, manifest, seed, r.requests, r.state, r.results, BuildIdentity.runtime());
        write(pdir.resolve("state.bin"), r.state);
        write(pdir.resolve("results.bin"), r.results);
        write(pdir.resolve("requests.bin"), r.requests);
        write(pdir.resolve("receipt.json"), Json.bytes(receipt));
        write(pdir.resolve("generation.json"), Json.bytes(published));
        Path target = publishedDir(lease.namespace(), lease.generation());
        Files.createDirectories(target.getParent());
        Files.move(pdir, target, StandardCopyOption.ATOMIC_MOVE);
        setCurrent(lease.namespace(), lease.generation());
        tables.remove(key(lease.namespace(), lease.generation()));
        pendingInfo.remove(key(lease.namespace(), lease.generation()));
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
  public void discard(Lease lease) {
    synchronized (lock) {
      checkedPending(lease);
      discardQuietly(lease);
    }
  }

  private void discardQuietly(Lease lease) {
    Path pdir = pendingDir(lease.namespace(), lease.generation());
    if (!Files.isDirectory(pdir)) {
      return;
    }
    try {
      GenerationInfo info =
          pendingInfo
              .getOrDefault(
                  key(lease.namespace(), lease.generation()),
                  Json.read(pdir.resolve("generation.json"), GenerationInfo.class))
              .with(GenerationStatus.DISCARDED);
      write(pdir.resolve("generation.json"), Json.bytes(info));
      Path target =
          root.resolve(lease.namespace()).resolve("discarded").resolve(lease.generation());
      Files.createDirectories(target.getParent());
      Files.move(pdir, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      tables.remove(key(lease.namespace(), lease.generation()));
      pendingInfo.remove(key(lease.namespace(), lease.generation()));
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
      for (String kind : List.of("published", "discarded")) {
        Path file =
            root.resolve(namespace).resolve(kind).resolve(generation).resolve("generation.json");
        if (Files.isRegularFile(file)) {
          GenerationInfo info = Json.read(file, GenerationInfo.class);
          if (kind.equals("published") && !verifyAncestry(namespace).contains(generation)) {
            return Optional.of(info.with(GenerationStatus.DISCARDED));
          }
          return Optional.of(info);
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
    synchronized (lock) {
      Path pdir = pendingDir(namespace, generation);
      if (!tables.containsKey(key(namespace, generation))) {
        throw new GenerationException(generation + " is not an open pending generation");
      }
      byte[] journal = read(pdir.resolve("journal.bin"));
      return rebuild(read(pdir.resolve("seed.bin")), journal).results;
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

  private record Rebuilt(byte[] state, byte[] results, byte[] requests) {}

  private static Rebuilt rebuild(byte[] seed, byte[] journal) {
    PolicyTable table = PolicyTable.ofValidated(Records.policies(seed));
    int n = journal.length / ENTRY;
    byte[] results = new byte[n * ResultRecord.LENGTH];
    byte[] requests = new byte[n * TransactionRecord.LENGTH];
    for (int i = 0; i < n; i++) {
      int base = i * ENTRY;
      System.arraycopy(
          journal, base, requests, i * TransactionRecord.LENGTH, TransactionRecord.LENGTH);
      System.arraycopy(
          journal,
          base + TransactionRecord.LENGTH,
          results,
          i * ResultRecord.LENGTH,
          ResultRecord.LENGTH);
      byte[] successor = new byte[PolicyRecord.LENGTH];
      System.arraycopy(
          journal, base + ENTRY - PolicyRecord.LENGTH, successor, 0, PolicyRecord.LENGTH);
      ResultRecord result =
          new ResultRecord(
              java.util.Arrays.copyOfRange(
                  journal,
                  base + TransactionRecord.LENGTH,
                  base + TransactionRecord.LENGTH + ResultRecord.LENGTH));
      if (Status.OKAY.name().equals(result.status())) {
        table.replace(new PolicyRecord(successor));
      }
    }
    byte[] state = table.size() == 0 ? new byte[0] : Records.join(table.rows());
    return new Rebuilt(state, results, requests);
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
