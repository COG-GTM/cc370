package insurance.ledger;

import insurance.contract.v001.ContractV001;
import insurance.contract.v001.Evaluation;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.TransactionRecord;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.Optional;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The running contract as the store sees it: how to re-evaluate a request over a master, the hash
 * of the rate table it uses and a content identity of the code that implements it.
 *
 * <p>The identity is a SHA-256 over the class bytes of the {@code insurance.contract.v001} and
 * {@code insurance.legacy.codec} packages (every {@code .class} resource in the code source that
 * loaded them, sorted by name, each hashed) plus the canonical rate table. It is computed from the
 * bytes actually loaded -- a directory of classes, a plain JAR or a Spring Boot nested JAR -- and
 * is therefore the same for the same compiled classes regardless of how they are packaged, and
 * different as soon as any class of the calculation core or the record codecs changes. It is not a
 * path and not a caller-supplied label. A generation pins it at creation; a claim refuses to resume
 * under a different one.
 */
public final class ContractBinding {
  public static final String IDENTITY_PREFIX = "contract:sha256:";

  private final ContractV001 contract;
  private final String rateTableSha256;
  private final String identity;

  private ContractBinding(ContractV001 contract, String rateTableSha256, String identity) {
    this.contract = contract;
    this.rateTableSha256 = rateTableSha256;
    this.identity = identity;
  }

  public static ContractBinding of(ContractV001 contract) {
    String rates = BuildIdentity.rateTableSha256(contract.rates());
    return new ContractBinding(contract, rates, identityOf(rates));
  }

  /** Test seam: a binding that reports a different identity or rate hash than it computes. */
  public static ContractBinding relabelled(
      ContractV001 contract, String rateTableSha256, String identity) {
    return new ContractBinding(contract, rateTableSha256, identity);
  }

  public ContractV001 contract() {
    return contract;
  }

  public String rateTableSha256() {
    return rateTableSha256;
  }

  public String identity() {
    return identity;
  }

  /** The same evaluation the stateful paths perform: contract over the master, or NPOL. */
  public Evaluation evaluate(Optional<PolicyRecord> master, TransactionRecord request) {
    return master
        .map(m -> contract.evaluate(m, request))
        .orElseGet(() -> contract.noPolicy(request));
  }

  // ---------------------------------------------------------------- identity

  static String identityOf(String rateTableSha256) {
    TreeMap<String, String> classes = new TreeMap<>();
    collect(ContractV001.class, "insurance/contract/v001/", classes);
    collect(PolicyRecord.class, "insurance/legacy/codec/", classes);
    if (classes.isEmpty()) {
      throw new IllegalStateException("no contract class bytes found; refusing an empty identity");
    }
    StringBuilder canonical = new StringBuilder();
    classes.forEach((name, sha) -> canonical.append(name).append('=').append(sha).append('\n'));
    canonical.append("rates=").append(rateTableSha256).append('\n');
    return IDENTITY_PREFIX + Sha256.of(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void collect(Class<?> anchor, String prefix, TreeMap<String, String> into) {
    CodeSource source = anchor.getProtectionDomain().getCodeSource();
    if (source == null || source.getLocation() == null) {
      throw new IllegalStateException(anchor + " has no code source");
    }
    URL location = source.getLocation();
    try {
      if ("file".equals(location.getProtocol())) {
        Path path = Path.of(location.toURI());
        if (Files.isDirectory(path)) {
          collectDirectory(path, prefix, into);
          return;
        }
        if (Files.isRegularFile(path)) {
          try (JarFile jar = new JarFile(path.toFile())) {
            collectJar(jar, prefix, into);
          }
          return;
        }
      }
      URLConnection connection = location.openConnection();
      if (connection instanceof JarURLConnection jarConnection) {
        collectJar(jarConnection.getJarFile(), prefix, into);
        return;
      }
      throw new IllegalStateException("unsupported code source " + location);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void collectDirectory(Path root, String prefix, TreeMap<String, String> into)
      throws IOException {
    Path dir = root.resolve(prefix);
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) files::iterator) {
        String name = root.relativize(p).toString().replace('\\', '/');
        if (Files.isRegularFile(p) && name.endsWith(".class")) {
          into.put(name, Sha256.of(Files.readAllBytes(p)));
        }
      }
    }
  }

  private static void collectJar(JarFile jar, String prefix, TreeMap<String, String> into)
      throws IOException {
    Enumeration<JarEntry> entries = jar.entries();
    while (entries.hasMoreElements()) {
      JarEntry entry = entries.nextElement();
      String name = entry.getName();
      if (name.startsWith(prefix) && name.endsWith(".class")) {
        try (InputStream in = jar.getInputStream(entry)) {
          into.put(name, Sha256.of(in.readAllBytes()));
        }
      }
    }
  }
}
